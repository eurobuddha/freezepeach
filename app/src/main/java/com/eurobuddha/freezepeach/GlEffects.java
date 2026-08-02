package com.eurobuddha.freezepeach;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES30;
import android.opengl.GLUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Native GLES 3.0 retro-effects engine — a faithful port of filtr's WebGL2 GLSL passes to Android.
 * Renders offscreen (headless EGL PBuffer + FBO) and reads back to a Bitmap so the editor can bake an
 * effect into the sent image. Stage 1: dithering. Stage 2: halftone, edge-detect, ASCII (glyph atlas).
 */
public final class GlEffects {

    public static final int MONO = 0, TONAL = 1, INDEXED = 2;
    public static final int BAYER2 = 8, BAYER4 = 9, BAYER8 = 10, BAYER16 = 11, CLUSTERED = 12, IGN = 14;

    private interface Uni { void set(int prog); }

    // ── shared prelude (from filtr common.ts) ──
    private static final String LIB =
            "#version 300 es\nprecision highp float;\nin vec2 v_uv; out vec4 fragColor;\n" +
            "float luma(vec3 c){ return dot(c, vec3(0.2126,0.7152,0.0722)); }\n" +
            "const float BAYER8[64] = float[64](\n" +
            " 0.,32.,8.,40.,2.,34.,10.,42., 48.,16.,56.,24.,50.,18.,58.,26.,\n" +
            " 12.,44.,4.,36.,14.,46.,6.,38., 60.,28.,52.,20.,62.,30.,54.,22.,\n" +
            " 3.,35.,11.,43.,1.,33.,9.,41., 51.,19.,59.,27.,49.,17.,57.,25.,\n" +
            " 15.,47.,7.,39.,13.,45.,5.,37., 63.,31.,55.,23.,61.,29.,53.,21.);\n" +
            "float bayer8(vec2 p){ int i=int(mod(p.y,8.0))*8+int(mod(p.x,8.0)); return (BAYER8[i]+0.5)/64.0; }\n" +
            "float hash21(vec2 p){ p=fract(p*vec2(123.34,456.21)); p+=dot(p,p+45.32); return fract(p.x*p.y); }\n";

    private static final String VERT =
            "#version 300 es\nin vec2 a_pos; out vec2 v_uv;\n" +
            "void main(){ v_uv = a_pos*0.5+0.5; gl_Position = vec4(a_pos,0.0,1.0); }\n";

    private static final String DITHER = LIB +
            "uniform sampler2D u_tex; uniform vec2 u_res;\n" +
            "uniform float u_method,u_colorMode,u_levels,u_gamma,u_intensity,u_palCount;\n" +
            "uniform vec3 u_fg,u_bg,u_pal[8];\n" +
            "float bayerN(vec2 p, float n){ float sum=0.0,div=0.0,scale=1.0;\n" +
            "  for(int i=0;i<4;i++){ if(float(i)>=n) break; vec2 c=mod(floor(p/scale),2.0);\n" +
            "    float b=(c.x==c.y)?(c.x==0.0?0.0:3.0):(c.x>c.y?2.0:1.0);\n" +
            "    sum+=b*pow(4.0,float(i)); div+=3.0*pow(4.0,float(i)); scale*=2.0; } return (sum+0.5)/(div+1.0); }\n" +
            "float threshold(vec2 px){ if(u_method<8.5) return bayerN(px,1.0); else if(u_method<9.5) return bayerN(px,2.0);\n" +
            "  else if(u_method<10.5) return bayer8(px); else if(u_method<11.5) return bayerN(px,4.0);\n" +
            "  else if(u_method<12.5){ vec2 c=mod(px,4.0); return clamp(length(c-1.5)/2.8,0.0,1.0); }\n" +
            "  return fract(52.9829189*fract(dot(floor(px), vec2(0.06711056,0.00583715)))); }\n" +
            "float quant(float v,float m,float L){ v+=(m-0.5)/max(L-1.0,1.0)*u_intensity; return clamp(floor(v*(L-1.0)+0.5),0.0,L-1.0)/(L-1.0); }\n" +
            "void main(){ vec3 src=texture(u_tex,v_uv).rgb; vec2 px=v_uv*u_res; float m=threshold(px);\n" +
            "  src=pow(src,vec3(1.0/max(u_gamma,0.01))); float l=luma(src);\n" +
            "  if(u_colorMode<0.5){ float b=step(m,l); fragColor=vec4(mix(u_bg,u_fg,b),1.0); }\n" +
            "  else if(u_colorMode<1.5){ float q=quant(l,m,u_levels); fragColor=vec4(mix(u_bg,u_fg,q),1.0); }\n" +
            "  else { float q=quant(l,m,u_palCount); int idx=int(clamp(q*(u_palCount-1.0),0.0,u_palCount-1.0)); fragColor=vec4(u_pal[idx],1.0); } }\n";

    private static final String HALFTONE = LIB +
            "uniform sampler2D u_tex; uniform vec2 u_res;\n" +
            "uniform float u_shape,u_dotScale,u_spacing,u_angle,u_invert,u_colorMode; uniform vec3 u_fg,u_bg;\n" +
            "void main(){ vec2 c=u_res*0.5; float a=radians(u_angle);\n" +
            "  mat2 R=mat2(cos(a),-sin(a),sin(a),cos(a)); mat2 Rt=mat2(cos(a),sin(a),-sin(a),cos(a));\n" +
            "  vec2 px=v_uv*u_res; vec2 rp=R*(px-c)+c; vec2 cellIdx=floor(rp/u_spacing);\n" +
            "  vec2 cc=(cellIdx+0.5)*u_spacing; vec2 srcPx=Rt*(cc-c)+c;\n" +
            "  vec3 s=texture(u_tex, clamp(srcPx/u_res,0.0,1.0)).rgb; float ink=clamp(luma(s),0.0,1.0);\n" +
            "  ink=mix(1.0-ink,ink,u_invert); float maxR=0.5*u_spacing*1.45*u_dotScale; float radius=sqrt(ink)*maxR;\n" +
            "  vec2 d=rp-cc; float dist; if(u_shape<0.5) dist=length(d); else if(u_shape<1.5) dist=max(abs(d.x),abs(d.y));\n" +
            "  else if(u_shape<2.5) dist=abs(d.x)+abs(d.y); else dist=abs(d.y);\n" +
            "  float inside=1.0-smoothstep(radius-1.0,radius+1.0,dist); vec3 inkC=(u_colorMode<0.5)?u_fg:s;\n" +
            "  fragColor=vec4(mix(u_bg,inkC,inside),1.0); }\n";

    private static final String EDGE = LIB +
            "uniform sampler2D u_tex; uniform vec2 u_res;\n" +
            "uniform float u_algo,u_threshold,u_lineWidth,u_invert,u_colorMode; uniform vec3 u_edge,u_bg;\n" +
            "float L(vec2 uv){ return luma(texture(u_tex,uv).rgb); }\n" +
            "void main(){ vec2 t=u_lineWidth/u_res;\n" +
            "  float tl=L(v_uv+t*vec2(-1,1)),tm=L(v_uv+t*vec2(0,1)),tr=L(v_uv+t*vec2(1,1));\n" +
            "  float ml=L(v_uv+t*vec2(-1,0)),mm=L(v_uv),mr=L(v_uv+t*vec2(1,0));\n" +
            "  float bl=L(v_uv+t*vec2(-1,-1)),bm=L(v_uv+t*vec2(0,-1)),br=L(v_uv+t*vec2(1,-1)); float mag;\n" +
            "  if(u_algo<1.5){ float w=(u_algo<0.5)?2.0:1.0; float gx=(tr+w*mr+br)-(tl+w*ml+bl); float gy=(tl+w*tm+tr)-(bl+w*bm+br); mag=length(vec2(gx,gy)); }\n" +
            "  else { mag=abs(8.0*mm-(tl+tm+tr+ml+mr+bl+bm+br)); }\n" +
            "  float e=smoothstep(u_threshold,u_threshold+0.12,mag); e=mix(e,1.0-e,u_invert);\n" +
            "  vec3 ec=(u_colorMode<0.5)?u_edge:texture(u_tex,v_uv).rgb; fragColor=vec4(mix(u_bg,ec,e),1.0); }\n";

    private static final String ASCII = LIB +
            "uniform sampler2D u_tex; uniform sampler2D u_atlas; uniform vec2 u_res;\n" +
            "uniform float u_count,u_cell,u_glyph,u_bmap,u_invert,u_colorMode,u_intensity; uniform vec3 u_char,u_bg;\n" +
            "void main(){ vec2 px=v_uv*u_res; vec2 cell=floor(px/u_cell); vec2 origin=cell*u_cell;\n" +
            "  vec2 center=(origin+0.5*u_cell)/u_res; vec3 srcC=texture(u_tex,center).rgb;\n" +
            "  float l=pow(clamp(luma(srcC),0.0,1.0),max(u_bmap,0.01)); l=mix(l,1.0-l,u_invert);\n" +
            "  float idx=clamp(floor(l*u_count),0.0,u_count-1.0); vec2 inCell=px-origin; vec2 gap=vec2((u_cell-u_glyph)*0.5);\n" +
            "  vec2 local=(inCell-gap)/u_glyph; float cov=0.0;\n" +
            "  if(local.x>=0.0&&local.x<=1.0&&local.y>=0.0&&local.y<=1.0){ float ax=(idx+local.x)/u_count; cov=texture(u_atlas,vec2(ax,local.y)).r; }\n" +
            "  vec3 ink=(u_colorMode<0.5)?u_char:srcC*u_intensity; fragColor=vec4(mix(u_bg,ink,cov),1.0); }\n";

    // post-processing (from filtr post.ts) — all single-pass, stack on top of an effect
    private static final String SCANLINES = LIB + "uniform sampler2D u_tex; uniform vec2 u_res; uniform float u_opacity,u_spacing;\n" +
            "void main(){ vec4 c=texture(u_tex,v_uv); float s=0.5+0.5*sin(v_uv.y*u_res.y/max(u_spacing,1.0)*3.14159*2.0); c.rgb*=1.0-u_opacity*(1.0-s); fragColor=c; }\n";
    private static final String CRT = LIB + "uniform sampler2D u_tex; uniform float u_amount;\n" +
            "void main(){ vec2 uv=v_uv*2.0-1.0; uv*=1.0+dot(uv,uv)*u_amount; uv=uv*0.5+0.5; if(uv.x<0.0||uv.x>1.0||uv.y<0.0||uv.y>1.0){ fragColor=vec4(0.0,0.0,0.0,1.0); return; } fragColor=texture(u_tex,uv); }\n";
    private static final String CHROMATIC = LIB + "uniform sampler2D u_tex; uniform vec2 u_res; uniform float u_offset;\n" +
            "void main(){ vec2 dir=(v_uv-0.5); vec2 o=dir*(u_offset/u_res*2.0)*length(u_res)*0.02; float r=texture(u_tex,v_uv+o).r; vec4 g=texture(u_tex,v_uv); float b=texture(u_tex,v_uv-o).b; fragColor=vec4(r,g.g,b,g.a); }\n";
    private static final String GRAIN = LIB + "uniform sampler2D u_tex; uniform vec2 u_res; uniform float u_time,u_intensity,u_size,u_speed;\n" +
            "void main(){ vec4 c=texture(u_tex,v_uv); vec2 p=floor(v_uv*u_res/max(u_size,1.0)); float g=hash21(p+floor(u_time*u_speed*0.06)*13.7); c.rgb+=(g-0.5)*(u_intensity/200.0); fragColor=vec4(clamp(c.rgb,0.0,1.0),c.a); }\n";
    private static final String BLOOM = LIB + "uniform sampler2D u_tex; uniform vec2 u_res; uniform float u_threshold,u_soft,u_intensity,u_radius;\n" +
            "void main(){ vec4 base=texture(u_tex,v_uv); vec3 sum=vec3(0.0); for(int i=0;i<24;i++){ float a=float(i)*2.39996323; float rad=sqrt((float(i)+0.5)/24.0)*u_radius; vec2 off=vec2(cos(a),sin(a))*rad/u_res; vec3 c=texture(u_tex,v_uv+off).rgb; float l=luma(c); float knee=max(u_soft,0.001); float w=clamp((l-u_threshold+knee)/(2.0*knee),0.0,1.0); w*=step(u_threshold-knee,l); sum+=c*w; } fragColor=vec4(base.rgb+sum/24.0*u_intensity*2.5,base.a); }\n";
    private static final String VIGNETTE = LIB + "uniform sampler2D u_tex; uniform float u_intensity,u_radius;\n" +
            "void main(){ vec4 c=texture(u_tex,v_uv); float d=length(v_uv-0.5)*1.41421; float v=smoothstep(0.9,u_radius,d); c.rgb*=1.0-v*u_intensity; fragColor=c; }\n";

    // ── public effects ──
    public static Bitmap dither(Bitmap src, final int method, final int colorMode, final int[] pal, final int fg, final int bg, final float gamma) {
        final int n = pal != null ? Math.min(pal.length, 8) : 2;
        return render(src, DITHER, null, prog -> {
            GLES30.glUniform1f(u(prog, "u_method"), method);
            GLES30.glUniform1f(u(prog, "u_colorMode"), colorMode);
            GLES30.glUniform1f(u(prog, "u_levels"), n);
            GLES30.glUniform1f(u(prog, "u_gamma"), gamma);
            GLES30.glUniform1f(u(prog, "u_intensity"), 1.0f);
            GLES30.glUniform1f(u(prog, "u_palCount"), n);
            GLES30.glUniform3f(u(prog, "u_fg"), r(fg), g(fg), b(fg));
            GLES30.glUniform3f(u(prog, "u_bg"), r(bg), g(bg), b(bg));
            float[] p = new float[24];
            if (pal != null) for (int i = 0; i < n; i++) { p[i*3] = r(pal[i]); p[i*3+1] = g(pal[i]); p[i*3+2] = b(pal[i]); }
            GLES30.glUniform3fv(u(prog, "u_pal"), 8, p, 0);
        });
    }
    public static Bitmap halftone(Bitmap src, final int shape, final float spacing, final float angle, final boolean color, final int fg, final int bg) {
        return render(src, HALFTONE, null, prog -> {
            GLES30.glUniform1f(u(prog, "u_shape"), shape); GLES30.glUniform1f(u(prog, "u_dotScale"), 1.0f);
            GLES30.glUniform1f(u(prog, "u_spacing"), spacing); GLES30.glUniform1f(u(prog, "u_angle"), angle);
            GLES30.glUniform1f(u(prog, "u_invert"), 0.0f); GLES30.glUniform1f(u(prog, "u_colorMode"), color ? 1f : 0f);
            GLES30.glUniform3f(u(prog, "u_fg"), r(fg), g(fg), b(fg)); GLES30.glUniform3f(u(prog, "u_bg"), r(bg), g(bg), b(bg));
        });
    }
    public static Bitmap edge(Bitmap src, final int algo, final float threshold, final float lineWidth, final boolean original, final int edge, final int bg) {
        return render(src, EDGE, null, prog -> {
            GLES30.glUniform1f(u(prog, "u_algo"), algo); GLES30.glUniform1f(u(prog, "u_threshold"), threshold);
            GLES30.glUniform1f(u(prog, "u_lineWidth"), lineWidth); GLES30.glUniform1f(u(prog, "u_invert"), 0.0f);
            GLES30.glUniform1f(u(prog, "u_colorMode"), original ? 1f : 0f);
            GLES30.glUniform3f(u(prog, "u_edge"), r(edge), g(edge), b(edge)); GLES30.glUniform3f(u(prog, "u_bg"), r(bg), g(bg), b(bg));
        });
    }
    public static Bitmap ascii(Bitmap src, final boolean color, final int charColor, final int bg, final int cellPx) {
        final String ramp = " .:-=+*oa#%@";
        final Bitmap atlas = buildAtlas(ramp, 40);
        return render(src, ASCII, atlas, prog -> {
            GLES30.glUniform1f(u(prog, "u_count"), ramp.length()); GLES30.glUniform1f(u(prog, "u_cell"), cellPx);
            GLES30.glUniform1f(u(prog, "u_glyph"), cellPx); GLES30.glUniform1f(u(prog, "u_bmap"), 1.0f);
            GLES30.glUniform1f(u(prog, "u_invert"), 0.0f); GLES30.glUniform1f(u(prog, "u_colorMode"), color ? 1f : 0f);
            GLES30.glUniform1f(u(prog, "u_intensity"), 1.0f);
            GLES30.glUniform3f(u(prog, "u_char"), r(charColor), g(charColor), b(charColor));
            GLES30.glUniform3f(u(prog, "u_bg"), r(bg), g(bg), b(bg));
        });
    }

    public static Bitmap scanlines(Bitmap src, final float opacity, final float spacing) {
        return render(src, SCANLINES, null, prog -> { GLES30.glUniform1f(u(prog, "u_opacity"), opacity); GLES30.glUniform1f(u(prog, "u_spacing"), spacing); });
    }
    public static Bitmap crt(Bitmap src, final float amount) {
        return render(src, CRT, null, prog -> GLES30.glUniform1f(u(prog, "u_amount"), amount));
    }
    public static Bitmap chromatic(Bitmap src, final float offset) {
        return render(src, CHROMATIC, null, prog -> GLES30.glUniform1f(u(prog, "u_offset"), offset));
    }
    public static Bitmap grain(Bitmap src, final float intensity, final float size) {
        return render(src, GRAIN, null, prog -> { GLES30.glUniform1f(u(prog, "u_time"), 1f); GLES30.glUniform1f(u(prog, "u_intensity"), intensity); GLES30.glUniform1f(u(prog, "u_size"), size); GLES30.glUniform1f(u(prog, "u_speed"), 1f); });
    }
    public static Bitmap bloom(Bitmap src, final float threshold, final float soft, final float intensity, final float radius) {
        return render(src, BLOOM, null, prog -> { GLES30.glUniform1f(u(prog, "u_threshold"), threshold); GLES30.glUniform1f(u(prog, "u_soft"), soft); GLES30.glUniform1f(u(prog, "u_intensity"), intensity); GLES30.glUniform1f(u(prog, "u_radius"), radius); });
    }
    public static Bitmap vignette(Bitmap src, final float intensity, final float radius) {
        return render(src, VIGNETTE, null, prog -> { GLES30.glUniform1f(u(prog, "u_intensity"), intensity); GLES30.glUniform1f(u(prog, "u_radius"), radius); });
    }

    private static Bitmap buildAtlas(String chars, int glyphPx) {
        int count = chars.length();
        Bitmap bm = Bitmap.createBitmap(glyphPx * count, glyphPx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(0xFFFFFFFF); p.setTypeface(Typeface.MONOSPACE);
        p.setTextSize(glyphPx * 0.82f); p.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = p.getFontMetrics();
        float baseline = glyphPx / 2f - (fm.ascent + fm.descent) / 2f;
        for (int i = 0; i < count; i++) c.drawText(String.valueOf(chars.charAt(i)), i * glyphPx + glyphPx / 2f, baseline, p);
        return bm;
    }

    // ── shared offscreen render ──
    // One persistent EGL context + surface + program cache, reused across renders. GL_LOCK serializes
    // every render so the shared display is never torn down or made-current from two threads at once.
    private static final Object GL_LOCK = new Object();
    private static EGLDisplay sDpy = EGL14.EGL_NO_DISPLAY;
    private static EGLContext sCtx = EGL14.EGL_NO_CONTEXT;
    private static EGLSurface sSurf = EGL14.EGL_NO_SURFACE;
    private static int sQuadVbo = -1;
    private static final java.util.HashMap<String, Integer> sProgs = new java.util.HashMap<>();

    private static boolean ensureContext() {
        if (sCtx != EGL14.EGL_NO_CONTEXT) return true;
        sDpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (sDpy == EGL14.EGL_NO_DISPLAY) return false;
        EGL14.eglInitialize(sDpy, new int[2], 0, new int[2], 1);
        int[] cfgAttr = { EGL14.EGL_RENDERABLE_TYPE, 0x40, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE };
        EGLConfig[] cfg = new EGLConfig[1]; int[] num = new int[1];
        EGL14.eglChooseConfig(sDpy, cfgAttr, 0, cfg, 0, 1, num, 0);
        if (num[0] == 0) return false;
        sCtx = EGL14.eglCreateContext(sDpy, cfg[0], EGL14.EGL_NO_CONTEXT, new int[]{ EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE }, 0);
        sSurf = EGL14.eglCreatePbufferSurface(sDpy, cfg[0], new int[]{ EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE }, 0);
        return sCtx != EGL14.EGL_NO_CONTEXT && sSurf != EGL14.EGL_NO_SURFACE;
    }

    private static int programFor(String frag) {
        Integer cached = sProgs.get(frag);
        if (cached != null) return cached;
        int p = program(frag);
        sProgs.put(frag, p);
        return p;
    }

    private static Bitmap render(Bitmap src, String frag, Bitmap atlas, Uni uni) {
        synchronized (GL_LOCK) {
            int w = src.getWidth(), h = src.getHeight();
            try {
                if (!ensureContext()) return src;
                EGL14.eglMakeCurrent(sDpy, sSurf, sSurf, sCtx);

                int prog = programFor(frag);
                if (prog == 0) return src;

                if (sQuadVbo < 0) {
                    FloatBuffer qb = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
                    qb.put(new float[]{ -1, -1, 1, -1, -1, 1, 1, 1 }).position(0);
                    int[] vbo = new int[1]; GLES30.glGenBuffers(1, vbo, 0);
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0]);
                    GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 32, qb, GLES30.GL_STATIC_DRAW);
                    sQuadVbo = vbo[0];
                }

                int[] tex = tex2d(src);
                int[] atex = atlas != null ? tex2d(atlas) : null;

                int[] fbo = new int[1]; GLES30.glGenFramebuffers(1, fbo, 0);
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0]);
                int[] rtex = new int[1]; GLES30.glGenTextures(1, rtex, 0);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rtex[0]);
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, rtex[0], 0);

                GLES30.glViewport(0, 0, w, h);
                GLES30.glUseProgram(prog);
                int aPos = GLES30.glGetAttribLocation(prog, "a_pos");
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, sQuadVbo);
                GLES30.glEnableVertexAttribArray(aPos);
                GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, 0);
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]);
                GLES30.glUniform1i(u(prog, "u_tex"), 0);
                if (atex != null) { GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atex[0]); GLES30.glUniform1i(u(prog, "u_atlas"), 1); }
                GLES30.glUniform2f(u(prog, "u_res"), w, h);
                uni.set(prog);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

                ByteBuffer out = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
                GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, out);
                Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                out.rewind(); result.copyPixelsFromBuffer(out);

                // free this render's GL objects (the context persists, so nothing else reclaims them)
                GLES30.glDeleteTextures(1, tex, 0);
                if (atex != null) GLES30.glDeleteTextures(1, atex, 0);
                GLES30.glDeleteFramebuffers(1, fbo, 0);
                GLES30.glDeleteTextures(1, rtex, 0);
                if (atlas != null) atlas.recycle();
                return result;
            } catch (Exception e) {
                return src;
            } finally {
                if (sDpy != EGL14.EGL_NO_DISPLAY)
                    EGL14.eglMakeCurrent(sDpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            }
        }
    }

    private static int[] tex2d(Bitmap b) {
        int[] t = new int[1]; GLES30.glGenTextures(1, t, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0);
        return t;
    }
    private static int program(String frag) {
        int vs = shader(GLES30.GL_VERTEX_SHADER, VERT), fs = shader(GLES30.GL_FRAGMENT_SHADER, frag);
        if (vs == 0 || fs == 0) return 0;
        int p = GLES30.glCreateProgram(); GLES30.glAttachShader(p, vs); GLES30.glAttachShader(p, fs); GLES30.glLinkProgram(p);
        int[] ok = new int[1]; GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        return ok[0] == 0 ? 0 : p;
    }
    private static int shader(int type, String src) {
        int s = GLES30.glCreateShader(type); GLES30.glShaderSource(s, src); GLES30.glCompileShader(s);
        int[] ok = new int[1]; GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        return ok[0] == 0 ? 0 : s;
    }
    private static int u(int prog, String name) { return GLES30.glGetUniformLocation(prog, name); }
    private static float r(int c) { return ((c >> 16) & 0xFF) / 255f; }
    private static float g(int c) { return ((c >> 8) & 0xFF) / 255f; }
    private static float b(int c) { return (c & 0xFF) / 255f; }

    private GlEffects() {}
}
