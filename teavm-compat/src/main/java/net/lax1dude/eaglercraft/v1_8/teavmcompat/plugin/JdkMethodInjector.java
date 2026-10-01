package net.lax1dude.eaglercraft.v1_8.teavmcompat.plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.teavm.model.AccessLevel;
import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassHolderTransformerContext;
import org.teavm.model.ClassReader;
import org.teavm.model.ElementModifier;
import org.teavm.model.FieldHolder;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodHolder;
import org.teavm.model.ReferenceCache;
import org.teavm.model.emit.ProgramEmitter;
import org.teavm.model.emit.ValueEmitter;
import org.teavm.model.ValueType;
import org.teavm.model.util.ModelUtils;
import org.teavm.parsing.ClassRefsRenamer;

/**
 * Grafts missing JDK methods onto classes that teavm-classlib 0.13 already
 * provides (where the teavm.properties package mapping cannot help, because
 * the classlib version of the class wins the name lookup).
 *
 * <p>Mechanism: for each target class we keep a "donor" class in
 * {@code net.lax1dude.eaglercraft.v1_8.teavmcompat.inject}. Donors are plain
 * Java compiled against the real JDK, so their references to java.* types are
 * already the names TeaVM's virtual classlib uses. When the target class is
 * transformed, every donor method/field whose descriptor the target does not
 * already have is program-copied into it, with two reference renames applied:
 * donor-class self references become the target class, and references to this
 * module's compat hierarchy (net.lax1dude...teavmcompat.java.util.TFoo etc.)
 * become their virtual JDK names (java.util.Foo), matching the
 * teavm.properties mapping exactly.</p>
 *
 * <p>Donor rules (enforced by convention, not tooling):</p>
 * <ul>
 * <li>no lambdas, anonymous/inner classes, string concatenation or static
 *     initializers — donor programs must be self-contained;</li>
 * <li>methods the donor only needs so javac can resolve calls that the TARGET
 *     already implements (placeholders) are simply never copied, because the
 *     target already has a method with that descriptor;</li>
 * <li>constructors are copied only for donors registered with
 *     {@code copyConstructors} (delegation via {@code this(...)} to a
 *     constructor the target already has is the supported pattern).</li>
 * </ul>
 *
 * <p>Additionally, methods whose descriptors cannot be expressed in Java
 * source (return-type-only overloads such as MethodHandle.invokeExact
 * variants, or descriptors naming package-private third-party classes) are
 * synthesized directly in IR as public methods that throw
 * {@link UnsupportedOperationException}. Most are instance methods; the
 * descriptors listed in {@code SYNTHETIC_STATIC_THROWERS} remain static.</p>
 */
public class JdkMethodInjector implements ClassHolderTransformer {

	private static final String COMPAT_ROOT = "net.lax1dude.eaglercraft.v1_8.teavmcompat.";
	private static final String INJECT_PKG = COMPAT_ROOT + "inject.";

	/** donor simple name -> target class; boolean = copy constructors too. */
	private static final Object[][] DONORS = {
		{ "CharacterInject", "java.lang.Character", false },
		{ "ClassInject", "java.lang.Class", false },
		{ "ClassLoaderInject", "java.lang.ClassLoader", false },
		{ "IntegerInject", "java.lang.Integer", false },
		{ "LongInject", "java.lang.Long", false },
		{ "MathInject", "java.lang.Math", false },
		{ "RuntimeInject", "java.lang.Runtime", false },
		{ "SecurityManagerInject", "java.lang.SecurityManager", false },
		{ "StringInject", "java.lang.String", true },
		{ "SystemInject", "java.lang.System", false },
		{ "RandomAccessFileInject", "java.io.RandomAccessFile", false },
		{ "FilesInject", "java.nio.file.Files", false },
		{ "Base64Inject", "java.util.Base64", false },
		{ "DateInject", "java.util.Date", false },
		{ "SpliteratorsInject", "java.util.Spliterators", false },
		{ "StreamSupportInject", "java.util.stream.StreamSupport", false },
		{ "ConcurrentHashMapInject", "java.util.concurrent.ConcurrentHashMap", true },
		{ "MethodHandlesInject", "java.lang.invoke.MethodHandles", false },
		{ "MethodTypeInject", "java.lang.invoke.MethodType", false },
		{ "MethodHandleInject", "java.lang.invoke.MethodHandle", false },
		{ "LookupInject", "java.lang.invoke.MethodHandles$Lookup", false },
		{ "ArrayInject", "java.lang.reflect.Array", false },
		{ "PackageInject", "java.lang.Package", false },
		{ "ThreadInject", "java.lang.Thread", true },
		{ "FieldInject", "java.lang.reflect.Field", false },
		{ "MethodInject", "java.lang.reflect.Method", false },
		{ "AccessControllerInject", "java.security.AccessController", false },
		{ "CharsetInject", "java.nio.charset.Charset", false },
		{ "ElementTypeInject", "java.lang.annotation.ElementType", false },
		// Phase 3.3b: surfaced once client Main.main became reachable
		{ "CollectionsInject", "java.util.Collections", false },
		{ "LocaleInject", "java.util.Locale", false },
		{ "PatternInject", "java.util.regex.Pattern", false },
		{ "FileInputStreamInject", "java.io.FileInputStream", false },
		{ "FileInject", "java.io.File", false },
		// Phase 3.3b cycle 5: full-client surface (icu4j excised, whole graph walked)
		{ "DoubleInject", "java.lang.Double", false },
		{ "FloatInject", "java.lang.Float", false },
		{ "InetAddressInject", "java.net.InetAddress", false },
		{ "Inet6AddressInject", "java.net.Inet6Address", false },
		{ "NetworkInterfaceInject", "java.net.NetworkInterface", false },
		{ "URLInject", "java.net.URL", false },
		{ "FileChannelInject", "java.nio.channels.FileChannel", false },
		{ "TimeUnitInject", "java.util.concurrent.TimeUnit", false },
		{ "AtomicReferenceArrayInject", "java.util.concurrent.atomic.AtomicReferenceArray", false },
		{ "ByteBufferInject", "java.nio.ByteBuffer", false },
	};

	/**
	 * Donors whose methods REPLACE the target's existing implementations
	 * (classlib bug fixes). Abstract donor methods are javac placeholders
	 * and are never copied; every concrete donor method overwrites the
	 * target's method of the same descriptor.
	 */
	private static final Object[][] REPLACE_DONORS = {
		// TeaVM 0.13 TConcurrentMap.computeIfAbsent returns putIfAbsent's
		// result directly — null on first insert (see ConcurrentMapInject)
		{ "ConcurrentMapInject", "java.util.concurrent.ConcurrentMap" },
		// TeaVM 0.13 TGZIPOutputStream.flush() deflates with Z_SYNC_FLUSH ->
		// Z_BUF_ERROR(-5) on empty input -> TDeflater throws. Restore the JDK
		// syncFlush=false behaviour so NbtIo.writeCompressed (profile + world
		// saves) stops crashing on close (see GZIPOutputStreamInject).
		{ "GZIPOutputStreamInject", "java.util.zip.GZIPOutputStream" },
		// ZIP uses raw DEFLATE (Inflater nowrap=true), which requires one dummy
		// input byte after each bounded entry. TeaVM's ZipFile stream omitted it,
		// causing valid resource-pack metadata to fail at EOF. Restore the JDK
		// fill behavior, including its tolerance for protected packs whose central
		// size/CRC metadata is intentionally inconsistent.
		{ "ZipFileInflaterInputStreamInject", "java.util.zip.ZipFile$ZipInflaterInputStream" },
		// netty ResourceLeakDetector.addExclusions(cls,"method") does cls.getDeclaredMethods()
		// and throws "Can't find '[toLeakAwareBuffer]'" when TeaVM's incomplete reflection
		// metadata omits it; AbstractByteBufAllocator's static init hits this, failing the
		// integrated-server LocalChannel bind. No-op it (leak detection is DISABLED on web).
		{ "ResourceLeakDetectorInject", "io.netty.util.ResourceLeakDetector" },
		// netty TypeParameterMatcher.find() needs generic-signature reflection (getGeneric
		// Superclass -> ParameterizedType) that TeaVM does not emit -> "unknown type
		// parameter 'I'" when MC's Connection (SimpleChannelInboundHandler<Packet>) is
		// constructed at LocalChannel connect. Replace with the accept-everything matcher.
		{ "TypeParameterMatcherInject", "io.netty.util.internal.TypeParameterMatcher" },
		// TThrowable.getSuppressed() reads a null `suppressed` array for native/JS-wrapped
		// exceptions -> "reading 'data'" crash inside netty rethrowIfFailed, masking the real
		// cause at every failed syncUninterruptibly. Return empty so it can never crash.
		{ "ThrowableInject", "java.lang.Throwable" },
		// TFormatter runs configureFormat() (defaultArgumentIndex++) before it knows the
		// conversion, so the no-arg %% conversion eats an argument slot and later conversions
		// read past the args array (JS undefined -> getClass() "reading 'constructor'").
		// Killed the F3 'minecraft:memory' entry. formatValue's '%' case gives the slot back
		// (see FormatWriterInject; spike-verified).
		{ "FormatWriterInject", "java.util.Formatter$FormatWriter" },
	};

	/**
	 * Throwing stubs for descriptors not expressible as Java source. All are
	 * generated as public instance methods throwing UnsupportedOperationException;
	 * every one of them sits behind runtime-guarded reflection fallbacks
	 * (netty PlatformDependent/Cleaner), so linking is all that is required.
	 */
	private static final Map<String, List<String>> SYNTHETIC_STATIC_THROWERS = Map.of(
		"java.util.Collections", List.of(
			"unmodifiableSequencedSet(Ljava/util/SequencedSet;)Ljava/util/SequencedSet;"
		)
	);

	private static final Map<String, List<String>> SYNTHETIC_THROWERS = Map.ofEntries(
		Map.entry("java.lang.invoke.MethodHandle", List.of(
			// return-type-only overload against invokeExact(J)J from the donor
			"invokeExact(J)V",
			// descriptor names a package-private netty class (Java25 cleaner)
			"invokeExact(I)Lio/netty/util/internal/CleanerJava25$CleanableDirectBufferImpl;",
			// Phase 3.3b cycle 5: netty ByteBuf VarHandle-backed accessors
			"invokeExact()V",
			"invokeExact(Ljava/nio/ByteBuffer;I)Ljava/nio/ByteBuffer;",
			"invokeExact(Ljava/nio/ByteBuffer;ILjava/nio/ByteBuffer;II)Ljava/nio/ByteBuffer;",
			"invokeExact(Ljava/nio/ByteBuffer;I[BII)Ljava/nio/ByteBuffer;",
			// cycle 6 cascade: fastutil/guava probe shape
			"invokeExact(Ljava/lang/Class;)Z",
				"invokeExact(Ljava/util/zip/Checksum;Ljava/nio/ByteBuffer;)V"
		)),
		// VarHandle accessors are signature-polymorphic: each reached shape
		// (guava AbstractFutureState, netty RefCnt — all runtime-guarded)
		// must exist as a plain method; several are return-type-only overloads
		// or name package-private third-party classes, so all go through IR.
		Map.entry("java.lang.invoke.VarHandle", List.of(
			"compareAndSet(Lcom/google/common/util/concurrent/AbstractFutureState;Lcom/google/common/util/concurrent/AbstractFuture$Listener;Lcom/google/common/util/concurrent/AbstractFuture$Listener;)Z",
			"compareAndSet(Lcom/google/common/util/concurrent/AbstractFutureState;Lcom/google/common/util/concurrent/AbstractFutureState$Waiter;Lcom/google/common/util/concurrent/AbstractFutureState$Waiter;)Z",
			"compareAndSet(Lcom/google/common/util/concurrent/AbstractFutureState;Ljava/lang/Object;Ljava/lang/Object;)Z",
			"getAcquire(Lio/netty/util/internal/RefCnt;)I",
			"getAndSet(Lcom/google/common/util/concurrent/AbstractFutureState;Lcom/google/common/util/concurrent/AbstractFuture$Listener;)Lcom/google/common/util/concurrent/AbstractFuture$Listener;",
			"getAndSet(Lcom/google/common/util/concurrent/AbstractFutureState;Lcom/google/common/util/concurrent/AbstractFutureState$Waiter;)Lcom/google/common/util/concurrent/AbstractFutureState$Waiter;",
			"get(Lio/netty/util/internal/RefCnt;)I",
			"get(Ljava/nio/ByteBuffer;I)I",
			"get(Ljava/nio/ByteBuffer;I)J",
			"set(Lio/netty/util/internal/RefCnt;I)V",
			"setRelease(Lcom/google/common/util/concurrent/AbstractFutureState$Waiter;Lcom/google/common/util/concurrent/AbstractFutureState$Waiter;)V",
			"setRelease(Lcom/google/common/util/concurrent/AbstractFutureState$Waiter;Ljava/lang/Thread;)V",
			// Phase 3.3b cycle 5: netty RefCnt + ByteBuf accessors
			"compareAndSet(Lio/netty/util/internal/RefCnt;II)Z",
			"get([BI)I",
			"get([BI)J",
			"getAndAdd(Lio/netty/util/internal/RefCnt;I)I",
			"getAndAdd(Lio/netty/util/internal/RefCnt;I)V",
			"set(Ljava/nio/ByteBuffer;II)V",
			"set(Ljava/nio/ByteBuffer;IJ)V",
			"set([BII)V",
			"set([BIJ)V",
			"setRelease(Lio/netty/util/internal/RefCnt;I)V",
				// cycle52: netty ByteBuf short accessors reached via the SP world graph
				"get([BI)S", "get(Ljava/nio/ByteBuffer;I)S", "set([BIS)V", "set(Ljava/nio/ByteBuffer;IS)V"
		)),
		// Phase 3.3b cycle 5: instance methods on dead paths (raw sockets, real
		// file channels, watch services, HTTP client) — the browser has none of
		// these; they must link but are never reached on the title path.
		// (CompletableFuture.applyToEither/exceptionallyAsync/exceptionallyComposeAsync
		// were here until cycle 20, when the resource reload hit applyToEither for
		// real — now implemented in the TCompletableFuture facade instead.)
		Map.entry("java.nio.channels.FileChannel", List.of(
			"position(J)Ljava/nio/channels/FileChannel;",
			"truncate(J)Ljava/nio/channels/FileChannel;",
			"tryLock()Ljava/nio/channels/FileLock;"
		)),
		Map.entry("java.nio.channels.spi.SelectorProvider", List.of(
			"openSelector()Ljava/nio/channels/spi/AbstractSelector;"
		)),
		Map.entry("java.io.FileOutputStream", List.of(
			"getChannel()Ljava/nio/channels/FileChannel;"
		)),
		Map.entry("java.nio.file.FileSystem", List.of(
			"getPathMatcher(Ljava/lang/String;)Ljava/nio/file/PathMatcher;",
			"newWatchService()Ljava/nio/file/WatchService;"
		)),
		Map.entry("java.util.regex.Matcher", List.of(
			"group(Ljava/lang/String;)Ljava/lang/String;"
		)),
		Map.entry("java.net.HttpURLConnection", List.of(
			"getContentLengthLong()J"
		)),
		Map.entry("java.io.BufferedReader", List.of(
			"transferTo(Ljava/io/Writer;)J"
		)),
		Map.entry("java.io.Reader", List.of(
			"readAllAsString()Ljava/lang/String;"
		)),
		Map.entry("java.lang.management.ThreadMXBean", List.of(
			"isObjectMonitorUsageSupported()Z"
		)),
		// cycle52: methods reached via the SP world/netty graph (dead on the SP path)
		Map.entry("sun.misc.Unsafe", List.of(
			"getShort(Ljava/lang/Object;J)S",
			"putShort(JS)V",
			"putShort(Ljava/lang/Object;JS)V"
		)),
		Map.entry("java.util.zip.Inflater", List.of(
			"inflate(Ljava/nio/ByteBuffer;)I",
			"setInput(Ljava/nio/ByteBuffer;)V"
		)),
		Map.entry("java.time.Duration", List.of(
			"toSeconds()J"
		)),
		Map.entry("java.util.Collections", List.of(
			"unmodifiableSequencedSet(Ljava/util/SequencedSet;)Ljava/util/SequencedSet;"
		)),
		Map.entry("java.net.Inet6Address", List.of(
			"getScopedInterface()Ljava/net/NetworkInterface;",
			"getScopeId()I"
		)),
		Map.entry("java.nio.file.Path", List.of(
			// resolve(String, String...) is now a REAL chaining impl (world loading reaches
			// it via Path.of/Paths.get) — see addPathResolveVarargs in transformClass.
			// cycle 8: PackSelectionScreen directory watcher (dead — no VFS watch)
			"register(Ljava/nio/file/WatchService;[Ljava/nio/file/WatchEvent$Kind;)Ljava/nio/file/WatchKey;"
		)),
		Map.entry("java.lang.reflect.Type", List.of(
			"getTypeName()Ljava/lang/String;"
		))
	);

	/**
	 * Classes whose remaining native methods get throwing bodies. JNA's
	 * natives are reached via oshi from net.minecraft.SystemReport, inside
	 * SystemReport.ignoreErrors (a catch-Throwable guard), so throwing
	 * UnsupportedOperationException at runtime is the correct degradation.
	 */
	private static final String[] NATIVE_STUB_CLASSES = {
		"com.sun.jna.Native",
		// Phase 3.3b cycle 10: JNI-backed classes with no browser implementation.
		// All are dead on web — netty's native socket transports (websockets are
		// used instead), the Tracy native profiler, and the Linux/macOS native
		// text-to-speech narrator. Their native methods get throwing bodies so the
		// program links; none are touched on the title-screen path.
		"io.netty.channel.epoll.Native",
		"io.netty.channel.epoll.NativeStaticallyReferencedJniMethods",
		"io.netty.channel.kqueue.Native",
		"io.netty.channel.kqueue.KQueueStaticallyReferencedJniMethods",
		"io.netty.channel.unix.ErrorsStaticallyReferencedJniMethods",
		"io.netty.channel.unix.LimitsStaticallyReferencedJniMethods",
		"io.netty.channel.unix.Buffer",
		"io.netty.channel.unix.Socket",
		"io.netty.channel.unix.FileDescriptor",
		// Fast dependency analysis conservatively retains concrete epoll/kqueue
		// socket wrappers as well as their shared native helpers. Browser traffic
		// never instantiates these; PlatformNetworking uses WebSocket channels.
		"io.netty.channel.epoll.LinuxSocket",
		"io.netty.channel.kqueue.BsdSocket",
		"io.netty.channel.kqueue.KQueueEventArray",
		"com.mojang.jtracy.TracyBindings",
		"com.mojang.text2speech.NarratorLinux$FliteLibrary",
		"com.mojang.text2speech.NarratorLinux$FliteLibrary$CmuUsKal16",
		"ca.weblite.objc.RuntimeUtils",
	};

	/**
	 * Methods whose bodies are replaced with {@code return null}. Guava's
	 * VarHandle probe only checks Class.forName("java.lang.invoke.VarHandle")
	 * — present here as a link-only shim — then runs VarHandleAtomicHelper's
	 * clinit UNGUARDED (MethodHandles.lookup() outside any try/catch).
	 * Returning null is guava's own "before Java 9" signal, steering it into
	 * the guarded Unsafe -> AtomicReferenceFieldUpdater -> synchronized
	 * fallback chain. Class name is guava-version-sensitive (33.6.0-jre).
	 */
	private static final Map<String, List<String>> METHOD_NULLERS = Map.of(
		"com.google.common.util.concurrent.AbstractFutureState$VarHandleAtomicHelperMaker$1", List.of(
			"tryMakeVarHandleAtomicHelper()Lcom/google/common/util/concurrent/AbstractFutureState$AtomicHelper;"
		)
	);

	/**
	 * Existing method bodies that only work with TeaVM's linear-memory backend.
	 * Fast analysis retains native-buffer virtual implementations even though the
	 * WasmGC client allocates heap buffers. Replacing only their private copy
	 * helpers keeps diagnostics strict while making the conservative graph link.
	 */
	private static final Map<String, List<String>> METHOD_THROWERS = Map.of(
		"java.nio.ByteBufferNative", List.of(
			"copy([BILorg/teavm/interop/Address;I)V",
			"copy(Lorg/teavm/interop/Address;[BII)V",
			"copy(Lorg/teavm/interop/Address;Lorg/teavm/interop/Address;I)V"
		),
		"java.nio.IntBufferNative", List.of(
			"copy([IILorg/teavm/interop/Address;I)V",
			"copy(Lorg/teavm/interop/Address;[III)V"
		),
		"java.nio.FloatBufferNative", List.of(
			"copy([FILorg/teavm/interop/Address;I)V",
			"copy(Lorg/teavm/interop/Address;[FII)V"
		)
	);

	private final ReferenceCache referenceCache = new ReferenceCache();
	private final Map<String, Object[]> donorsByTarget = new HashMap<>();
	private final Map<String, Object[]> replaceDonorsByTarget = new HashMap<>();

	public JdkMethodInjector() {
		for (Object[] entry : DONORS) {
			donorsByTarget.put((String) entry[1], entry);
		}
		for (Object[] entry : REPLACE_DONORS) {
			replaceDonorsByTarget.put((String) entry[1], entry);
		}
	}

	@Override
	public void transformClass(ClassHolder cls, ClassHolderTransformerContext context) {
		Object[] donorEntry = donorsByTarget.get(cls.getName());
		if (donorEntry != null) {
			injectDonor(cls, INJECT_PKG + donorEntry[0], (Boolean) donorEntry[2], false, context);
		}
		Object[] replaceEntry = replaceDonorsByTarget.get(cls.getName());
		if (replaceEntry != null) {
			injectDonor(cls, INJECT_PKG + replaceEntry[0], false, true, context);
		}
		List<String> synthetic = SYNTHETIC_THROWERS.get(cls.getName());
		if (synthetic != null) {
			List<String> staticSynthetic = SYNTHETIC_STATIC_THROWERS.get(cls.getName());
			for (String descriptor : synthetic) {
				addThrowingMethod(cls, MethodDescriptor.parse(descriptor),
						staticSynthetic != null && staticSynthetic.contains(descriptor), context);
			}
		}
		if (cls.getName().equals("java.nio.file.Path")) {
			addPathResolveVarargs(cls, context);
		}
		List<String> nulled = METHOD_NULLERS.get(cls.getName());
		if (nulled != null) {
			for (String descriptor : nulled) {
				nullMethodBody(cls, MethodDescriptor.parse(descriptor), context);
			}
		}
		List<String> throwers = METHOD_THROWERS.get(cls.getName());
		if (throwers != null) {
			for (String descriptor : throwers) {
				throwMethodBody(cls, MethodDescriptor.parse(descriptor), context);
			}
		}
		for (String nativeStubClass : NATIVE_STUB_CLASSES) {
			if (nativeStubClass.equals(cls.getName())) {
				stubNativeMethods(cls, context);
				break;
			}
		}
	}

	/** Replaces every bodyless native method with a throwing implementation. */
	private void stubNativeMethods(ClassHolder cls, ClassHolderTransformerContext context) {
		for (MethodHolder method : cls.getMethods().toArray(new MethodHolder[0])) {
			if (method.getModifiers().contains(ElementModifier.NATIVE) && method.getProgram() == null) {
				method.getModifiers().remove(ElementModifier.NATIVE);
				ProgramEmitter pe = ProgramEmitter.create(method, context.getHierarchy());
				pe.construct(UnsupportedOperationException.class,
						pe.constant("eagler teavm-compat: native method stub " + cls.getName() + "."
								+ method.getName()))
						.raise();
			}
		}
	}

	private void injectDonor(ClassHolder target, String donorName, boolean copyConstructors, boolean replace,
			ClassHolderTransformerContext context) {
		ClassReader donorReader = context.getHierarchy().getClassSource().get(donorName);
		if (donorReader == null) {
			context.getDiagnostics().error(null, "eagler teavm-compat: donor class {{c0}} not found on classpath",
					donorName);
			return;
		}
		ClassHolder donor = ModelUtils.copyClass(donorReader);
		ClassRefsRenamer renamer = new ClassRefsRenamer(referenceCache,
				name -> mapReference(name, donorName, target.getName()));
		ClassHolder renamed = renamer.rename(donor);

		for (MethodHolder method : renamed.getMethods().toArray(new MethodHolder[0])) {
			if (method.getName().equals("<clinit>")) {
				continue;
			}
			if (method.getName().equals("<init>") && !copyConstructors) {
				continue;
			}
			if (method.getProgram() == null) {
				continue; // abstract donor method = javac placeholder, never copied
			}
			MethodHolder existing = target.getMethod(method.getDescriptor());
			if (existing != null) {
				if (!replace) {
					continue; // placeholder for a method the target already has
				}
				target.removeMethod(existing);
			}
			renamed.removeMethod(method);
			target.addMethod(method);
		}
		for (FieldHolder field : renamed.getFields().toArray(new FieldHolder[0])) {
			if (target.getField(field.getName()) != null) {
				continue;
			}
			renamed.removeField(field);
			target.addField(field);
		}
	}

	/**
	 * Reference mapping applied to copied donor programs: the donor class
	 * itself becomes the target class (so {@code this}-typed and static
	 * self-references resolve), and compat-hierarchy names are rewritten to
	 * their virtual JDK names, mirroring META-INF/teavm.properties.
	 */
	private static String mapReference(String name, String donorName, String targetName) {
		if (name.equals(donorName)) {
			return targetName;
		}
		if (name.startsWith(COMPAT_ROOT)) {
			String rest = name.substring(COMPAT_ROOT.length());
			if (rest.equals("replace.TUUID")) {
				return "java.util.UUID";
			}
			int firstDot = rest.indexOf('.');
			if (firstDot > 0) {
				String top = rest.substring(0, firstDot);
				if (top.equals("java") || top.equals("javax") || top.equals("jdk") || top.equals("sun")) {
					int lastDot = rest.lastIndexOf('.');
					String simple = rest.substring(lastDot + 1);
					if (simple.startsWith("T")) {
						simple = simple.substring(1);
					}
					return rest.substring(0, lastDot + 1) + simple;
				}
			}
		}
		return name;
	}

	/**
	 * Real (non-throwing) body for {@code Path.resolve(String first, String... more)}:
	 * {@code return EaglerNioHelper.resolveVarargs(this, first, more);}. World loading
	 * reaches this via {@code Path.of}/{@code Paths.get}.
	 */
	private void addPathResolveVarargs(ClassHolder cls, ClassHolderTransformerContext context) {
		MethodDescriptor descriptor = MethodDescriptor
				.parse("resolve(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;");
		if (cls.getMethod(descriptor) != null) {
			return;
		}
		MethodHolder method = new MethodHolder(descriptor);
		method.setLevel(AccessLevel.PUBLIC);
		ProgramEmitter pe = ProgramEmitter.create(method, context.getHierarchy());
		ValueType pathType = ValueType.object("java.nio.file.Path");
		ValueEmitter self = pe.var(0, pathType);
		ValueEmitter first = pe.var(1, ValueType.object("java.lang.String"));
		ValueEmitter more = pe.var(2, ValueType.arrayOf(ValueType.object("java.lang.String")));
		ValueEmitter result = pe.invoke("net.lax1dude.eaglercraft.v1_8.teavmcompat.EaglerNioHelper",
				"resolveVarargs", pathType, self, first, more);
		result.returnValue();
		cls.addMethod(method);
	}

	private void addThrowingMethod(ClassHolder cls, MethodDescriptor descriptor, boolean staticMethod,
			ClassHolderTransformerContext context) {
		if (cls.getMethod(descriptor) != null) {
			return;
		}
		MethodHolder method = new MethodHolder(descriptor);
		method.setLevel(AccessLevel.PUBLIC);
		method.getModifiers().add(ElementModifier.FINAL);
		if (staticMethod) {
			method.getModifiers().add(ElementModifier.STATIC);
		}
		ProgramEmitter pe = ProgramEmitter.create(method, context.getHierarchy());
		pe.construct(UnsupportedOperationException.class,
				pe.constant("eagler teavm-compat: " + cls.getName() + "." + descriptor + " is a link-only stub"))
				.raise();
		cls.addMethod(method);
	}

	private void nullMethodBody(ClassHolder cls, MethodDescriptor descriptor,
			ClassHolderTransformerContext context) {
		MethodHolder method = cls.getMethod(descriptor);
		if (method == null) {
			context.getDiagnostics().error(null,
					"eagler teavm-compat: METHOD_NULLERS target " + cls.getName() + "." + descriptor
							+ " not found (library version changed?)");
			return;
		}
		ProgramEmitter pe = ProgramEmitter.create(method, context.getHierarchy());
		pe.constantNull(method.getResultType()).returnValue();
	}

	private void throwMethodBody(ClassHolder cls, MethodDescriptor descriptor,
			ClassHolderTransformerContext context) {
		MethodHolder method = cls.getMethod(descriptor);
		if (method == null) {
			context.getDiagnostics().error(null,
					"eagler teavm-compat: METHOD_THROWERS target " + cls.getName() + "." + descriptor
							+ " not found (library version changed?)");
			return;
		}
		method.getModifiers().remove(ElementModifier.NATIVE);
		ProgramEmitter pe = ProgramEmitter.create(method, context.getHierarchy());
		pe.construct(UnsupportedOperationException.class,
				pe.constant("eagler teavm-compat: native buffer path is unavailable in WasmGC"))
				.raise();
	}

}
