package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.file.attribute;

/**
 * java.nio.file.attribute.UserPrincipal — interface facade. In the real JDK it
 * extends java.security.Principal (absent from teavm-classlib), so it is reduced
 * here to the single getName() method it inherits. No principal is ever
 * resolved in the browser VFS.
 */
public interface TUserPrincipal {

	String getName();

}
