package net.minecraft.client.renderer.feature;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.jspecify.annotations.Nullable;

public abstract class RenderTypeFeatureRenderer<Submit extends SubmitNode> implements FeatureRenderer<Submit> {
   private RenderTypeFeatureRenderer.@Nullable Group currentGroup;
   private final List<RenderTypeFeatureRenderer.Group> groups = new ArrayList<>();
   private int groupCount;

   protected abstract void buildGroup(FeatureFrameContext context, List<Submit> submits);

   protected final VertexConsumer getVertexBuilder(final RenderType renderType) {
      return this.currentGroup().getVertexBuilder(renderType);
   }

   private RenderTypeFeatureRenderer.Group currentGroup() {
      return Objects.requireNonNull(this.currentGroup, "Not preparing group");
   }

   @Override
   public final void prepareGroup(final FeatureFrameContext context, final List<Submit> submits, final boolean strictlyOrdered) {
      if (this.groupCount < this.groups.size()) {
         this.currentGroup = this.groups.get(this.groupCount);
      } else {
         this.currentGroup = new RenderTypeFeatureRenderer.Group();
         this.groups.add(this.currentGroup);
      }
      this.currentGroup.begin(context.stagedVertexBuffer(), !strictlyOrdered);
      this.buildGroup(context, submits);
      this.groupCount++;
      this.currentGroup = null;
   }

   @Override
   public void executeGroup(final FeatureFrameContext context, final int groupIndex, final List<Submit> submits, final boolean strictlyOrdered) {
      RenderTypeFeatureRenderer.Group group = this.groups.get(groupIndex);

      for (int i = 0; i < group.draws.size(); i++) {
         PreparedRenderType renderType = group.drawRenderTypes.get(i);
         StagedVertexBuffer.ExecuteInfo info = context.stagedVertexBuffer().getExecuteInfo(group.draws.get(i));
         if (info != null) {
            renderType.drawFromBuffer(info);
         }
      }
   }

   @Override
   public void finishExecute(final FeatureFrameContext context) {
      for (int i = 0; i < this.groupCount; i++) {
         this.groups.get(i).clear();
      }
      this.groupCount = 0;
   }

   private static class Group {
      private @Nullable StagedVertexBuffer stagedBuffer;
      private boolean canReorder;
      private final List<StagedVertexBuffer.Draw> draws = new ArrayList<>();
      private final List<PreparedRenderType> drawRenderTypes = new ArrayList<>();
      private final Map<RenderType, StagedVertexBuffer.Draw> drawsByRenderType = new IdentityHashMap<>();
      private final Map<PreparedRenderType, StagedVertexBuffer.Draw> drawsByPreparedType = new HashMap<>();
      private @Nullable RenderType lastRenderType;
      private StagedVertexBuffer.@Nullable Draw lastDraw;

      private void begin(final StagedVertexBuffer stagedBuffer, final boolean canReorder) {
         this.stagedBuffer = stagedBuffer;
         this.canReorder = canReorder;
      }

      private void clear() {
         this.draws.clear();
         this.drawRenderTypes.clear();
         this.drawsByRenderType.clear();
         this.drawsByPreparedType.clear();
         this.lastRenderType = null;
         this.lastDraw = null;
         this.stagedBuffer = null;
      }

      public VertexConsumer getVertexBuilder(final RenderType renderType) {
         if (this.lastDraw == null || this.lastRenderType != renderType || !renderType.canConsolidateConsecutiveGeometry()) {
            this.lastDraw = this.getOrAddDraw(renderType);
            this.lastRenderType = renderType;
         }

         return Objects.requireNonNull(this.stagedBuffer).getVertexBuilder(this.lastDraw);
      }

      private StagedVertexBuffer.Draw getOrAddDraw(final RenderType renderType) {
         boolean consolidate = this.canReorder && renderType.canConsolidateConsecutiveGeometry();
         if (consolidate) {
            StagedVertexBuffer.Draw existing = this.drawsByRenderType.get(renderType);
            if (existing != null) {
               return existing;
            }
         }

         PreparedRenderType preparedRenderType = renderType.prepare();
         if (consolidate) {
            StagedVertexBuffer.Draw existing = this.drawsByPreparedType.get(preparedRenderType);
            if (existing != null) {
               this.drawsByRenderType.put(renderType, existing);
               return existing;
            }
         }

         VertexSorting quadSorting = renderType.sortOnUpload() ? RenderSystem.getProjectionType().vertexSorting() : null;
         StagedVertexBuffer.Draw draw = Objects.requireNonNull(this.stagedBuffer)
            .appendDraw(renderType.format(), renderType.primitiveTopology(), quadSorting);
         this.draws.add(draw);
         this.drawRenderTypes.add(preparedRenderType);
         if (consolidate) {
            this.drawsByRenderType.put(renderType, draw);
            this.drawsByPreparedType.put(preparedRenderType, draw);
         }
         return draw;
      }
   }
}
