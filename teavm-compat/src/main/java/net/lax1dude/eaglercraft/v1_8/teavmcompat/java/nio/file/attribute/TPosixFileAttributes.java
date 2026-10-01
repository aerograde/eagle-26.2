package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.file.attribute;

import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/**
 * java.nio.file.attribute.PosixFileAttributes — interface facade. Extends the
 * classlib-provided BasicFileAttributes. group() returns GroupPrincipal in the
 * real JDK (out of scope), so it is omitted; owner()/permissions() are declared.
 */
public interface TPosixFileAttributes extends BasicFileAttributes {

	TUserPrincipal owner();

	TGroupPrincipal group();

	Set<TPosixFilePermission> permissions();

}
