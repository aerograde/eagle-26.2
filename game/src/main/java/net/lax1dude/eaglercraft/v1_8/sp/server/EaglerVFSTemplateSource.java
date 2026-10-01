package net.lax1dude.eaglercraft.v1_8.sp.server;

import java.util.Optional;
import java.util.stream.Stream;

import com.mojang.datafixers.DataFixer;

import net.lax1dude.eaglercraft.v1_8.EaglerInputStream;
import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;
import net.minecraft.core.HolderGetter;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.loader.TemplateSource;

/**
 * Hosted-mode replacement for DirectoryTemplateSource: world structure templates
 * (the generated/ dir) are gzip NBT blobs on the Eagler VFS at
 * worlds/&lt;level&gt;/generated/&lt;ns&gt;/structure/&lt;path&gt;.nbt — the write side
 * is the hosted branch in StructureTemplateManager.save.
 */
public class EaglerVFSTemplateSource extends TemplateSource {

	private static final Logger logger = LogManager.getLogger("EaglerVFSTemplateSource");

	private final String levelId;

	public EaglerVFSTemplateSource(DataFixer fixerUpper, HolderGetter<Block> blockLookup, String levelId) {
		super(fixerUpper, blockLookup);
		this.levelId = levelId;
	}

	@Override
	public Optional<StructureTemplate> load(Identifier id) {
		byte[] bytes = EaglerVFSWorldStorage.readStructureTemplateBytes(levelId, id);
		if(bytes == null) {
			return Optional.empty();
		}
		return this.load(() -> new EaglerInputStream(bytes), false, e -> {
			logger.error("Couldn't load structure {} from the worlds DB", id);
			logger.error(e);
		});
	}

	@Override
	public Stream<Identifier> list() {
		return EaglerVFSWorldStorage.listStructureTemplates(levelId).stream();
	}

}
