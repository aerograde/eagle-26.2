package net.minecraft.client.renderer.chunk;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

public class SectionCompiler {
   private final boolean ambientOcclusion;
   private final boolean cutoutLeaves;
   private final BlockStateModelSet blockModelSet;
   private final FluidStateModelSet fluidModelSet;
   private final BlockColors blockColors;

   public SectionCompiler(
      final boolean ambientOcclusion,
      final boolean cutoutLeaves,
      final BlockStateModelSet blockModelSet,
      final FluidStateModelSet fluidModelSet,
      final BlockColors blockColors
   ) {
      this.ambientOcclusion = ambientOcclusion;
      this.cutoutLeaves = cutoutLeaves;
      this.blockModelSet = blockModelSet;
      this.fluidModelSet = fluidModelSet;
      this.blockColors = blockColors;
   }

   // Mesh-worker plan Phase C: expose the two render-config booleans this compiler was built
   // with so the WorkerSectionDispatcher can stamp them into each off-thread job's snapshot
   // flags (design doc §4.2; cutoutLeaves is also the H9 mutable-static re-applied worker-side).
   public boolean ambientOcclusion() {
      return this.ambientOcclusion;
   }

   public boolean cutoutLeaves() {
      return this.cutoutLeaves;
   }

   // Mesh-worker plan Phase B: widened from the concrete RenderSectionRegion to the
   // BlockAndTintGetter interface so a headless worker-side region substitute
   // (net.lax1dude.eaglercraft.v1_8.mesh.WorkerRenderSectionRegion) can run this
   // exact compiler off-thread. RenderSectionRegion implements BlockAndTintGetter, so
   // the inline caller (SectionRenderDispatcher.CompileTask.doTask) is unaffected, and
   // the only non-interface use below — CrashReportCategory.populateBlockDetails —
   // takes a LevelHeightAccessor, which BlockAndTintGetter already extends.
   public SectionCompiler.Results compile(
      final SectionPos sectionPos, final BlockAndTintGetter region, final VertexSorting vertexSorting, final SectionBufferBuilderPack builders
   ) {
      SectionCompiler.Results results = new SectionCompiler.Results();
      BlockPos minPos = sectionPos.origin();
      BlockPos maxPos = minPos.offset(15, 15, 15);
      VisGraph visGraph = new VisGraph();
      BlockModelLighter.enableCaching();
      ModelBlockRenderer blockRenderer = new ModelBlockRenderer(this.ambientOcclusion, true, this.blockColors);
      FluidRenderer fluidRenderer = new FluidRenderer(this.fluidModelSet);
      Map<ChunkSectionLayer, BufferBuilder> startedLayers = new EnumMap<>(ChunkSectionLayer.class);
      BlockQuadOutput quadOutput = (x, y, z, quad, instance) -> {
         BufferBuilder builder = this.getOrBeginLayer(startedLayers, builders, quad.materialInfo().layer());
         builder.putBlockBakedQuad(x, y, z, quad, instance);
      };
      BlockQuadOutput fastLeavesSolidOutput = (x, y, z, quad, instance) -> {
         BufferBuilder builder = this.getOrBeginLayer(startedLayers, builders, ChunkSectionLayer.SOLID);
         builder.putBlockBakedQuad(x, y, z, quad, instance);
      };
      FluidRenderer.Output fluidOutput = layerx -> this.getOrBeginLayer(startedLayers, builders, layerx);

      for (BlockPos pos : BlockPos.betweenClosed(minPos, maxPos)) {
         BlockState blockState = region.getBlockState(pos);
         if (!blockState.isAir()) {
            try {
               if (blockState.isSolidRender()) {
                  visGraph.setOpaque(pos);
               }

               if (blockState.hasBlockEntity()) {
                  BlockEntity blockEntity = region.getBlockEntity(pos);
                  if (blockEntity != null) {
                     this.handleBlockEntity(results, blockEntity);
                  }
               }

               FluidState fluidState = blockState.getFluidState();
               if (!fluidState.isEmpty()) {
                  fluidRenderer.tesselate(region, pos, fluidOutput, blockState, fluidState);
               }

               if (blockState.getRenderShape() == RenderShape.MODEL) {
                  BlockStateModel blockModel = this.blockModelSet.get(blockState);
                  // Keep FAST's opaque path only for a model made entirely from
                  // vanilla-style full cube boundary faces. Hybrid resource-pack
                  // foliage can contain a cube plus sparse rotated planes; forcing
                  // only that cube to SOLID exposes its transparent-black texels as
                  // seams. One model-level flag protects every face while preserving
                  // the vanilla six-face optimization and avoiding a hot per-quad
                  // branch during tessellation.
                  boolean fastLeavesSolid = ModelBlockRenderer.forceOpaque(this.cutoutLeaves, blockState)
                     && !blockModel.hasMaterialFlag(BakedQuad.FLAG_NON_FULL_BLOCK_GEOMETRY);
                  blockRenderer.tesselateBlock(
                     fastLeavesSolid ? fastLeavesSolidOutput : quadOutput,
                     SectionPos.sectionRelative(pos.getX()),
                     SectionPos.sectionRelative(pos.getY()),
                     SectionPos.sectionRelative(pos.getZ()),
                     region,
                     pos,
                     blockState,
                     blockModel,
                     blockState.getSeed(pos)
                  );
               }
            } catch (Throwable t) {
               CrashReport report = CrashReport.forThrowable(t, "Tesselating block in world");
               CrashReportCategory category = report.addCategory("Block being tesselated");
               CrashReportCategory.populateBlockDetails(category, region, pos, blockState);
               throw new ReportedException(report);
            }
         }
      }

      for (Entry<ChunkSectionLayer, BufferBuilder> entry : startedLayers.entrySet()) {
         ChunkSectionLayer layer = entry.getKey();
         MeshData mesh = entry.getValue().build();
         if (mesh != null) {
            if (layer == ChunkSectionLayer.TRANSLUCENT) {
               results.transparencyState = mesh.sortQuads(builders.buffer(layer), vertexSorting);
            }

            results.renderedLayers.put(layer, mesh);
         }
      }

      BlockModelLighter.clearCache();
      results.visibilitySet = visGraph.resolve();
      return results;
   }

   private BufferBuilder getOrBeginLayer(
      final Map<ChunkSectionLayer, BufferBuilder> startedLayers, final SectionBufferBuilderPack buffers, final ChunkSectionLayer layer
   ) {
      BufferBuilder builder = startedLayers.get(layer);
      if (builder == null) {
         ByteBufferBuilder buffer = buffers.buffer(layer);
         builder = new BufferBuilder(buffer, PrimitiveTopology.QUADS, layer.vertexFormat());
         startedLayers.put(layer, builder);
      }

      return builder;
   }

   private <E extends BlockEntity> void handleBlockEntity(final SectionCompiler.Results results, final E blockEntity) {
      results.blockEntities.add(blockEntity);
   }

   public static final class Results {
      public final List<BlockEntity> blockEntities = new ArrayList<>();
      public final Map<ChunkSectionLayer, MeshData> renderedLayers = new EnumMap<>(ChunkSectionLayer.class);
      public VisibilitySet visibilitySet = new VisibilitySet();
      public MeshData.@Nullable SortState transparencyState;

      public void release() {
         this.renderedLayers.values().forEach(MeshData::close);
      }
   }
}
