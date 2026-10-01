/*
 * Copyright (c) 2026 lax1dude / Eagler 26.2. All Rights Reserved.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.
 */

package net.lax1dude.eaglercraft.v1_8.internal.webgpu;

import com.mojang.blaze3d.pipeline.BlendEquation;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

import org.teavm.jso.JSObject;

/**
 * WebGPU backend (increment 1) compiled pipeline: WGSL module (from
 * {@link WGSLShaderPack}) + bind-group layouts (from the manifest bind-group
 * conventions) + render state (blend/depth/cull/topology/vertex layout from the
 * vanilla {@link RenderPipeline} descriptor — the manifest carries no state).
 *
 * Bind-group convention (manifest): group(0)=global UBOs (dynamic-offset uniform
 * buffers), group(1)=textures (tex=N, sampler=N+1), group(2)=storage buffers.
 *
 * Known increment-1 simplifications (see webgpu-backend-plan.md): depth attachment
 * format assumed depth32float; texture sampleType assumed float; frontFace ccw.
 */
public final class WebGPUPipeline implements CompiledRenderPipeline {

	private final RenderPipeline info;
	private final WGSLShaderPack.Program program;
	private final JSObject pipelineHandle; // null => invalid
	private final JSObject[] groupLayouts; // by group index (0..numGroups-1), may hold empty layouts
	private final int numGroups;

	private WebGPUPipeline(final RenderPipeline info, final WGSLShaderPack.Program program, final JSObject pipelineHandle,
			final JSObject[] groupLayouts, final int numGroups) {
		this.info = info;
		this.program = program;
		this.pipelineHandle = pipelineHandle;
		this.groupLayouts = groupLayouts;
		this.numGroups = numGroups;
	}

	public static WebGPUPipeline invalid(final RenderPipeline info, final WGSLShaderPack.Program program) {
		return new WebGPUPipeline(info, program, null, new JSObject[0], 0);
	}

	public RenderPipeline info() {
		return this.info;
	}

	public WGSLShaderPack.Program program() {
		return this.program;
	}

	public JSObject handle() {
		return this.pipelineHandle;
	}

	public JSObject groupLayout(final int group) {
		return group < this.numGroups ? this.groupLayouts[group] : null;
	}

	public int numGroups() {
		return this.numGroups;
	}

	@Override
	public boolean isValid() {
		return this.pipelineHandle != null;
	}

	// ==== build ====

	@org.teavm.jso.JSBody(params = { "arr" }, script = "arr.push(null);")
	private static native void pushNull(JSObject arr);

	public static WebGPUPipeline build(final WebGPUDevice device, final RenderPipeline info, final WGSLShaderPack.Program program) {
		if (program == null) {
			return invalid(info, null);
		}
		final JSObject dev = device.deviceHandle();

		JSObject module = WebGPU.deviceCreateShaderModule(dev, program.wgsl, info.getLocation().toString());

		// ---- bind group layouts ----
		int maxGroup = 0;
		if (program.texNames.length > 0) {
			maxGroup = Math.max(maxGroup, 1);
		}
		if (program.storNames.length > 0) {
			maxGroup = Math.max(maxGroup, 2);
		}
		int numGroups = maxGroup + 1;
		JSObject[] groupLayouts = new JSObject[numGroups];
		int visAll = 0x3; // VERTEX | FRAGMENT

		// group 0: global UBOs (dynamic offset)
		{
			JSObject entries = WebGPU.newArr();
			for (int i = 0; i < program.uboBindings.length; i++) {
				JSObject e = WebGPU.newObj();
				WebGPU.putInt(e, "binding", program.uboBindings[i]);
				WebGPU.putInt(e, "visibility", visAll);
				JSObject buf = WebGPU.newObj();
				WebGPU.putStr(buf, "type", "uniform");
				// increment 1: exact slice offset is baked into a per-draw bind group
				// (no dynamic offsets yet — that packing is an increment-2 throughput win).
				WebGPU.putBool(buf, "hasDynamicOffset", false);
				WebGPU.putObj(e, "buffer", buf);
				WebGPU.push(entries, e);
			}
			JSObject desc = WebGPU.newObj();
			WebGPU.putObj(desc, "entries", entries);
			groupLayouts[0] = WebGPU.deviceCreateBindGroupLayout(dev, desc);
		}
		// group 1: textures + samplers
		if (numGroups > 1) {
			JSObject entries = WebGPU.newArr();
			for (int i = 0; i < program.texNames.length; i++) {
				JSObject te = WebGPU.newObj();
				WebGPU.putInt(te, "binding", program.texTex[i]);
				WebGPU.putInt(te, "visibility", visAll);
				JSObject tex = WebGPU.newObj();
				WebGPU.putStr(tex, "sampleType", "float");
				WebGPU.putStr(tex, "viewDimension", "2d");
				WebGPU.putObj(te, "texture", tex);
				WebGPU.push(entries, te);

				JSObject se = WebGPU.newObj();
				WebGPU.putInt(se, "binding", program.texSmp[i]);
				WebGPU.putInt(se, "visibility", visAll);
				JSObject smp = WebGPU.newObj();
				WebGPU.putStr(smp, "type", "filtering");
				WebGPU.putObj(se, "sampler", smp);
				WebGPU.push(entries, se);
			}
			JSObject desc = WebGPU.newObj();
			WebGPU.putObj(desc, "entries", entries);
			groupLayouts[1] = WebGPU.deviceCreateBindGroupLayout(dev, desc);
		}
		// group 2: storage buffers
		if (numGroups > 2) {
			JSObject entries = WebGPU.newArr();
			for (int i = 0; i < program.storNames.length; i++) {
				JSObject e = WebGPU.newObj();
				WebGPU.putInt(e, "binding", program.storBindings[i]);
				WebGPU.putInt(e, "visibility", visAll);
				JSObject buf = WebGPU.newObj();
				WebGPU.putStr(buf, "type", "read-only-storage");
				WebGPU.putObj(e, "buffer", buf);
				WebGPU.push(entries, e);
			}
			JSObject desc = WebGPU.newObj();
			WebGPU.putObj(desc, "entries", entries);
			groupLayouts[2] = WebGPU.deviceCreateBindGroupLayout(dev, desc);
		}

		JSObject layoutList = WebGPU.newArr();
		for (int g = 0; g < numGroups; g++) {
			WebGPU.push(layoutList, groupLayouts[g]);
		}
		JSObject pipelineLayoutDesc = WebGPU.newObj();
		WebGPU.putObj(pipelineLayoutDesc, "bindGroupLayouts", layoutList);
		JSObject pipelineLayout = WebGPU.deviceCreatePipelineLayout(dev, pipelineLayoutDesc);

		// ---- vertex state ----
		JSObject vertexBuffers = WebGPU.newArr();
		int runningLocation = 0;
		VertexFormat[] vfs = info.getVertexFormatBindings();
		for (int slot = 0; slot < vfs.length; slot++) {
			VertexFormat vf = vfs[slot];
			if (vf == null) {
				continue;
			}
			JSObject attrs = WebGPU.newArr();
			for (VertexFormatElement el : vf.getElements()) {
				String vfmt = WebGPUConst.vertexFormat(el.format());
				if (vfmt == null) {
					System.err.println("webgpu: unmapped vertex format " + el.format() + " for attribute " + el.name()
							+ " in pipeline " + info.getLocation() + " (increment-2 format)");
					vfmt = "float32x4";
				}
				JSObject a = WebGPU.newObj();
				WebGPU.putStr(a, "format", vfmt);
				WebGPU.putInt(a, "offset", el.offset());
				WebGPU.putInt(a, "shaderLocation", runningLocation++);
				WebGPU.push(attrs, a);
			}
			JSObject vbl = WebGPU.newObj();
			WebGPU.putInt(vbl, "arrayStride", vf.getVertexSize());
			WebGPU.putStr(vbl, "stepMode", "vertex");
			WebGPU.putObj(vbl, "attributes", attrs);
			WebGPU.push(vertexBuffers, vbl);
		}
		JSObject vertex = WebGPU.newObj();
		WebGPU.putObj(vertex, "module", module);
		WebGPU.putStr(vertex, "entryPoint", "vs");
		WebGPU.putObj(vertex, "buffers", vertexBuffers);

		// ---- fragment state ----
		JSObject fragment = null;
		if (program.hasFragment) {
			JSObject targets = WebGPU.newArr();
			ColorTargetState[] cts = info.getColorTargetStates();
			for (ColorTargetState state : cts) {
				if (state == null) {
					pushNull(targets);
					continue;
				}
				String texFmt = WebGPUConst.textureFormat(state.format());
				if (texFmt == null) {
					texFmt = "rgba8unorm";
				}
				JSObject t = WebGPU.newObj();
				WebGPU.putStr(t, "format", texFmt);
				WebGPU.putInt(t, "writeMask", state.writeMask());
				if (state.blendFunction().isPresent()) {
					WebGPU.putObj(t, "blend", buildBlend(state.blendFunction().get()));
				}
				WebGPU.push(targets, t);
			}
			fragment = WebGPU.newObj();
			WebGPU.putObj(fragment, "module", module);
			WebGPU.putStr(fragment, "entryPoint", "fs");
			WebGPU.putObj(fragment, "targets", targets);
		}

		// ---- primitive state ----
		JSObject primitive = WebGPU.newObj();
		WebGPU.putStr(primitive, "topology", WebGPUConst.topology(info.getPrimitiveTopology()));
		WebGPU.putStr(primitive, "frontFace", "ccw");
		WebGPU.putStr(primitive, "cullMode", info.isCull() ? "back" : "none");

		// ---- depth-stencil state ----
		JSObject depthStencil = null;
		DepthStencilState dss = info.getDepthStencilState();
		if (dss != null) {
			depthStencil = WebGPU.newObj();
			// increment-1 assumption: main depth attachment is depth32float (MC default).
			WebGPU.putStr(depthStencil, "format", "depth32float");
			WebGPU.putBool(depthStencil, "depthWriteEnabled", dss.writeDepth());
			WebGPU.putStr(depthStencil, "depthCompare", WebGPUConst.compare(dss.depthTest()));
			if (dss.depthBiasConstant() != 0.0F || dss.depthBiasScaleFactor() != 0.0F) {
				WebGPU.putInt(depthStencil, "depthBias", (int) dss.depthBiasConstant());
				WebGPU.putNum(depthStencil, "depthBiasSlopeScale", dss.depthBiasScaleFactor());
			}
		}

		// ---- assemble pipeline descriptor ----
		JSObject desc = WebGPU.newObj();
		WebGPU.putObj(desc, "layout", pipelineLayout);
		WebGPU.putStr(desc, "label", info.getLocation().toString());
		WebGPU.putObj(desc, "vertex", vertex);
		if (fragment != null) {
			WebGPU.putObj(desc, "fragment", fragment);
		}
		WebGPU.putObj(desc, "primitive", primitive);
		if (depthStencil != null) {
			WebGPU.putObj(desc, "depthStencil", depthStencil);
		}

		JSObject pipeline = WebGPU.deviceCreateRenderPipeline(dev, desc);
		if (pipeline == null) {
			System.err.println("webgpu: pipeline creation failed for " + info.getLocation());
			return invalid(info, program);
		}
		return new WebGPUPipeline(info, program, pipeline, groupLayouts, numGroups);
	}

	private static JSObject buildBlend(final BlendFunction bf) {
		JSObject blend = WebGPU.newObj();
		WebGPU.putObj(blend, "color", buildBlendComponent(bf.color()));
		WebGPU.putObj(blend, "alpha", buildBlendComponent(bf.alpha()));
		return blend;
	}

	private static JSObject buildBlendComponent(final BlendEquation eq) {
		JSObject c = WebGPU.newObj();
		WebGPU.putStr(c, "operation", WebGPUConst.blendOp(eq.op()));
		WebGPU.putStr(c, "srcFactor", WebGPUConst.blendFactor(eq.sourceFactor()));
		WebGPU.putStr(c, "dstFactor", WebGPUConst.blendFactor(eq.destFactor()));
		return c;
	}
}
