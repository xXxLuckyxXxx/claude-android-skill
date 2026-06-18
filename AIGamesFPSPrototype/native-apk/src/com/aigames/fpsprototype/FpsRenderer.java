package com.aigames.fpsprototype;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Minimal first-person GLES 2.0 renderer: a lit, checkered floor and a handful
 * of shaded boxes (the same arena layout as the Unity test scene). The camera
 * is driven by InputState (move stick + look delta).
 */
public class FpsRenderer implements GLSurfaceView.Renderer {

    private static final float MOVE_SPEED = 4.5f;     // m/s
    private static final float LOOK_SENS = 0.0045f;   // rad per px
    private static final int STRIDE = 24;             // 6 floats * 4 bytes

    private final InputState input;
    private final float[] lookTmp = new float[2];

    private int program;
    private int aPos, aNormal, uMVP, uModel, uColor, uChecker, uLightDir;

    private FloatBuffer cube;
    private FloatBuffer floor;

    private final float[] proj = new float[16];
    private final float[] view = new float[16];
    private final float[] model = new float[16];
    private final float[] mvp = new float[16];

    // Camera state.
    private float px = 0f, py = 1.6f, pz = 9f;
    private float yaw = 0f, pitch = -0.08f;
    private long lastNanos;

    // Boxes: posX, posY, posZ, scaleX, scaleY, scaleZ, r, g, b.
    private static final float[][] BOXES = {
        {-4f, 1.5f, 4f,  1.2f, 3.0f, 1.2f, 0.55f, 0.57f, 0.60f},
        { 4f, 1.5f, 4f,  1.2f, 3.0f, 1.2f, 0.55f, 0.57f, 0.60f},
        {-2f, 0.75f, 0f, 1.5f, 1.5f, 1.5f, 0.72f, 0.45f, 0.20f},
        { 2.5f, 0.75f, 1f, 1.5f, 1.5f, 1.5f, 0.72f, 0.45f, 0.20f},
        { 0f, 2.0f, 12f, 20f, 4.0f, 1.0f, 0.50f, 0.52f, 0.55f},
        { 0f, 0.6f, 6f,  0.7f, 1.2f, 0.7f, 0.10f, 0.80f, 1.00f},
    };

    public FpsRenderer(InputState input) {
        this.input = input;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.06f, 0.07f, 0.10f, 1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);

        program = buildProgram(VERT_SRC, FRAG_SRC);
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aNormal = GLES20.glGetAttribLocation(program, "aNormal");
        uMVP = GLES20.glGetUniformLocation(program, "uMVP");
        uModel = GLES20.glGetUniformLocation(program, "uModel");
        uColor = GLES20.glGetUniformLocation(program, "uColor");
        uChecker = GLES20.glGetUniformLocation(program, "uChecker");
        uLightDir = GLES20.glGetUniformLocation(program, "uLightDir");

        cube = makeBuffer(CUBE_DATA);
        floor = makeBuffer(FLOOR_DATA);
        lastNanos = System.nanoTime();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        float aspect = (float) width / Math.max(1, height);
        Matrix.perspectiveM(proj, 0, 70f, aspect, 0.1f, 300f);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        long now = System.nanoTime();
        float dt = (now - lastNanos) / 1_000_000_000f;
        lastNanos = now;
        if (dt > 0.1f) dt = 0.1f;

        updateCamera(dt);

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glUseProgram(program);
        GLES20.glUniform3f(uLightDir, -0.4f, 1.0f, -0.3f);

        Matrix.setIdentityM(model, 0);
        drawMesh(floor, 6, model, 0f, 0f, 0f, 1f);

        for (float[] b : BOXES) {
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, b[0], b[1], b[2]);
            Matrix.scaleM(model, 0, b[3], b[4], b[5]);
            drawMesh(cube, 36, model, b[6], b[7], b[8], 0f);
        }
    }

    private void updateCamera(float dt) {
        input.consumeLook(lookTmp);
        yaw += lookTmp[0] * LOOK_SENS;
        pitch -= lookTmp[1] * LOOK_SENS;
        float limit = 1.48f;                 // ~85 degrees
        if (pitch > limit) pitch = limit;
        if (pitch < -limit) pitch = -limit;

        float mx = input.moveX();
        float my = input.moveY();
        float fwdX = (float) Math.sin(yaw), fwdZ = -(float) Math.cos(yaw);
        float rgtX = (float) Math.cos(yaw), rgtZ = (float) Math.sin(yaw);
        px += (rgtX * mx + fwdX * my) * MOVE_SPEED * dt;
        pz += (rgtZ * mx + fwdZ * my) * MOVE_SPEED * dt;

        float cosP = (float) Math.cos(pitch);
        float ldx = cosP * (float) Math.sin(yaw);
        float ldy = (float) Math.sin(pitch);
        float ldz = -cosP * (float) Math.cos(yaw);
        Matrix.setLookAtM(view, 0, px, py, pz, px + ldx, py + ldy, pz + ldz, 0f, 1f, 0f);
    }

    private void drawMesh(FloatBuffer buf, int vertexCount, float[] m,
                          float r, float g, float b, float checker) {
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0);
        Matrix.multiplyMM(mvp, 0, mvp, 0, m, 0);
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(uModel, 1, false, m, 0);
        GLES20.glUniform3f(uColor, r, g, b);
        GLES20.glUniform1f(uChecker, checker);

        buf.position(0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, STRIDE, buf);
        buf.position(3);
        GLES20.glEnableVertexAttribArray(aNormal);
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, STRIDE, buf);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount);
    }

    private static FloatBuffer makeBuffer(float[] data) {
        FloatBuffer fb = ByteBuffer.allocateDirect(data.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(data).position(0);
        return fb;
    }

    private static int buildProgram(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }

    private static final float[] FLOOR_DATA = floorQuad(40f);

    private static float[] floorQuad(float s) {
        return new float[] {
            -s, 0f, -s, 0f, 1f, 0f,   s, 0f, -s, 0f, 1f, 0f,   s, 0f, s, 0f, 1f, 0f,
            -s, 0f, -s, 0f, 1f, 0f,   s, 0f, s, 0f, 1f, 0f,   -s, 0f, s, 0f, 1f, 0f,
        };
    }

    private static final float[] CUBE_DATA = unitCube();

    private static float[] unitCube() {
        float h = 0.5f;
        return new float[] {
            // +X
             h, -h, -h, 1, 0, 0,   h, h, -h, 1, 0, 0,   h, h, h, 1, 0, 0,
             h, -h, -h, 1, 0, 0,   h, h, h, 1, 0, 0,    h, -h, h, 1, 0, 0,
            // -X
            -h, -h, -h, -1, 0, 0, -h, h, h, -1, 0, 0,  -h, h, -h, -1, 0, 0,
            -h, -h, -h, -1, 0, 0, -h, -h, h, -1, 0, 0, -h, h, h, -1, 0, 0,
            // +Y
            -h, h, -h, 0, 1, 0,    h, h, h, 0, 1, 0,    h, h, -h, 0, 1, 0,
            -h, h, -h, 0, 1, 0,   -h, h, h, 0, 1, 0,    h, h, h, 0, 1, 0,
            // -Y
            -h, -h, -h, 0, -1, 0,  h, -h, -h, 0, -1, 0, h, -h, h, 0, -1, 0,
            -h, -h, -h, 0, -1, 0,  h, -h, h, 0, -1, 0, -h, -h, h, 0, -1, 0,
            // +Z
            -h, -h, h, 0, 0, 1,    h, -h, h, 0, 0, 1,   h, h, h, 0, 0, 1,
            -h, -h, h, 0, 0, 1,    h, h, h, 0, 0, 1,   -h, h, h, 0, 0, 1,
            // -Z
            -h, -h, -h, 0, 0, -1,  h, h, -h, 0, 0, -1,  h, -h, -h, 0, 0, -1,
            -h, -h, -h, 0, 0, -1, -h, h, -h, 0, 0, -1,  h, h, -h, 0, 0, -1,
        };
    }

    private static final String VERT_SRC =
        "uniform mat4 uMVP; uniform mat4 uModel;" +
        "attribute vec4 aPos; attribute vec3 aNormal;" +
        "varying vec3 vNormal; varying vec3 vWorld;" +
        "void main(){ vWorld=(uModel*aPos).xyz; vNormal=aNormal; gl_Position=uMVP*aPos; }";

    private static final String FRAG_SRC =
        "precision mediump float;" +
        "varying vec3 vNormal; varying vec3 vWorld;" +
        "uniform vec3 uColor; uniform float uChecker; uniform vec3 uLightDir;" +
        "void main(){" +
        "  vec3 n=normalize(vNormal);" +
        "  float d=max(dot(n,normalize(uLightDir)),0.0);" +
        "  vec3 base=uColor;" +
        "  if(uChecker>0.5){" +
        "    float c=mod(floor(vWorld.x)+floor(vWorld.z),2.0);" +
        "    base=mix(vec3(0.12,0.13,0.15),vec3(0.22,0.24,0.28),c);" +
        "  }" +
        "  vec3 col=base*(0.28+0.8*d);" +
        "  gl_FragColor=vec4(col,1.0);" +
        "}";
}
