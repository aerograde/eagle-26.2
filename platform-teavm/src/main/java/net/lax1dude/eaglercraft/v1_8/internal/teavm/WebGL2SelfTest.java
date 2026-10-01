/*
 * Copyright (c) 2026 lax1dude / Eagler 26.2. All Rights Reserved.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.
 */

package net.lax1dude.eaglercraft.v1_8.internal.teavm;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.DeviceInfo;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformRuntime;
import net.lax1dude.eaglercraft.v1_8.internal.webgl2.WebGL2Buffer;
import net.lax1dude.eaglercraft.v1_8.internal.webgl2.WebGL2Device;
import net.lax1dude.eaglercraft.v1_8.internal.webgl2.WebGL2Program;
import net.lax1dude.eaglercraft.v1_8.internal.webgl2.WebGL2RenderingContextExt;
import net.lax1dude.eaglercraft.v1_8.internal.webgl2.WebGL2ShaderModule;
import net.lax1dude.eaglercraft.v1_8.internal.webgl2.WebGL2ShaderTranslator;

import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Uint8Array;
import org.teavm.jso.webgl.WebGLProgram;
import org.teavm.jso.webgl.WebGLVertexArrayObject;

/**
 * Phase 3.3a headless self-test for the WebGL2 GpuDeviceBackend. Opt-in only
 * (eaglercraftXOpts.webgl2SelfTest === true); {@link ClientMain} guards the call
 * so this is dead code otherwise (mirrors {@link InputSelfTest}).
 *
 * It proves the core plumbing WITHOUT the full RenderPipeline/RenderPass/
 * CommandEncoder machinery (which the task explicitly permits): acquire the
 * WebGL2 context, construct {@link WebGL2Device} directly (bypassing the GpuBackend
 * selection seam, which is Phase 3.3b), create a triangle vertex buffer through
 * the device, compile a GLSL-300-es shader pair THROUGH
 * {@link WebGL2ShaderTranslator} from real "#version 330" source, link a program,
 * bind a VAO, clear to black, draw the triangle red over the whole viewport, and
 * read back the centre pixel — expecting 255,0,0.
 */
public class WebGL2SelfTest {

	@JSBody(params = { "msg" }, script = "console.log(msg);")
	private static native void log(String msg);

	private static final int GL_ARRAY_BUFFER = 34962;
	private static final int GL_FLOAT = 5126;
	private static final int GL_TRIANGLES = 4;
	private static final int GL_COLOR_BUFFER_BIT = 16384;
	private static final int GL_SCISSOR_TEST = 3089;
	private static final int GL_RGBA = 6408;
	private static final int GL_UNSIGNED_BYTE = 5121;

	// A single oversized triangle that fully covers clip space, so the centre
	// pixel is guaranteed inside it: (-1,-1), (3,-1), (-1,3).
	private static final float[] TRIANGLE = new float[] { -1.0f, -1.0f, 3.0f, -1.0f, -1.0f, 3.0f };

	private static final String VSH_330 = "#version 330 core\n" + "in vec2 aPos;\n" + "void main() {\n" + "\tgl_Position = vec4(aPos, 0.0, 1.0);\n" + "}\n";

	private static final String FSH_330 = "#version 330 core\n" + "out vec4 fragColor;\n" + "void main() {\n"
			+ "\tfragColor = vec4(1.0, 0.0, 0.0, 1.0);\n" + "}\n";

	public static void run() {
		log("eagler-webgl2-selftest: begin");
		try {
			WebGL2RenderingContextExt gl = PlatformRuntime.getWebGL2Context();
			if (gl == null) {
				log("eagler-webgl2-selftest: FAIL could not acquire WebGL2 context");
				return;
			}
			log("eagler-webgl2-selftest: context acquired");

			WebGL2Device device = new WebGL2Device(gl, null, new GpuDebugOptions(0, false, false, false));
			DeviceInfo info = device.getDeviceInfo();
			log("eagler-webgl2-selftest: device ok renderer=\"" + info.name() + "\" backend=" + info.backendName()
					+ " maxTexSize=" + info.limits().maxTextureSize() + " uboAlign=" + info.limits().minUniformOffsetAlignment()
					+ " zZeroToOne=" + info.isZZeroToOne());

			// ==== GLSL 330 -> 300es translation (prove the pass) ====
			String esVsh = WebGL2ShaderTranslator.translate(VSH_330, ShaderType.VERTEX);
			String esFsh = WebGL2ShaderTranslator.translate(FSH_330, ShaderType.FRAGMENT);
			log("eagler-webgl2-selftest: translated vsh line1=\"" + firstLine(esVsh) + "\"");
				log("eagler-webgl2-selftest: translated fsh has precision=" + esFsh.contains("precision highp float"));
				log("eagler-webgl2-selftest: shader-pack api macro="
						+ esFsh.contains("#define EAGLER_SHADER_PACK_API 1"));

			WebGL2ShaderModule vsh = WebGL2Program.compileShaderModule(gl, VSH_330, "selftest.vsh", ShaderType.VERTEX);
			WebGL2ShaderModule fsh = WebGL2Program.compileShaderModule(gl, FSH_330, "selftest.fsh", ShaderType.FRAGMENT);
			if (vsh == WebGL2ShaderModule.INVALID_SHADER || fsh == WebGL2ShaderModule.INVALID_SHADER) {
				log("eagler-webgl2-selftest: FAIL shader compile");
				return;
			}
			log("eagler-webgl2-selftest: shaders compiled (300 es)");

			WebGL2Program program = WebGL2Program.link(gl, vsh, fsh, null, "selftest");
			WebGLProgram programId = program.getProgramId();
			int loc = gl.getAttribLocation(programId, "aPos");
			if (loc < 0) {
				log("eagler-webgl2-selftest: FAIL aPos attribute location not found");
				return;
			}
			log("eagler-webgl2-selftest: program linked aPos@" + loc);

			// ==== vertex buffer via the device (exercises WebGL2Buffer + fromJavaBuffer) ====
			ByteBuffer triData = ByteBuffer.allocateDirect(TRIANGLE.length * 4).order(ByteOrder.LITTLE_ENDIAN);
			FloatBuffer fb = triData.asFloatBuffer();
			fb.put(TRIANGLE);
			triData.position(0);
			triData.limit(TRIANGLE.length * 4);
			GpuBuffer vbo = device.createBuffer(() -> "selftest-tri", GpuBuffer.USAGE_VERTEX, triData);
			log("eagler-webgl2-selftest: vertex buffer uploaded (" + vbo.size() + " bytes)");

			// ==== VAO ====
			WebGLVertexArrayObject vao = gl.createVertexArray();
			gl.bindVertexArray(vao);
			gl.bindBuffer(GL_ARRAY_BUFFER, ((WebGL2Buffer) vbo).handle());
			gl.enableVertexAttribArray(loc);
			gl.vertexAttribPointer(loc, 2, GL_FLOAT, false, 0, 0);

			// ==== draw ====
			int w = Math.max(1, PlatformRuntime.canvas.getWidth());
			int h = Math.max(1, PlatformRuntime.canvas.getHeight());
			gl.disable(GL_SCISSOR_TEST);
			gl.viewport(0, 0, w, h);
			gl.clearColor(0.0f, 0.0f, 0.0f, 1.0f);
			gl.clear(GL_COLOR_BUFFER_BIT);
			gl.useProgram(programId);
			gl.drawArrays(GL_TRIANGLES, 0, 3);

			int err = gl.getError();
			if (err != 0) {
				log("eagler-webgl2-selftest: WARN gl error after draw = " + err);
			}

			// ==== read back centre pixel ====
			Uint8Array px = Uint8Array.create(4);
			gl.readPixels(w / 2, h / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
			int r = px.get(0) & 0xFF;
			int g = px.get(1) & 0xFF;
			int b = px.get(2) & 0xFF;

			if (r == 255 && g == 0 && b == 0) {
				log("eagler-webgl2-selftest: PASS pixel=" + r + "," + g + "," + b);
			} else {
				log("eagler-webgl2-selftest: FAIL pixel=" + r + "," + g + "," + b + " (expected 255,0,0) viewport=" + w + "x" + h);
			}
		} catch (Throwable t) {
			log("eagler-webgl2-selftest: EXCEPTION " + t.toString());
			EagRuntime.debugPrintStackTrace(t);
		}
	}

	private static String firstLine(final String s) {
		int nl = s.indexOf('\n');
		return nl < 0 ? s : s.substring(0, nl);
	}
}
