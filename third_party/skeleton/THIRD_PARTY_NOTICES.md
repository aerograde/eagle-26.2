# Third-party notices for the project skeleton

This is a provenance and notice inventory for the artifacts selected by the
project-skeleton allowlist. It is not a blanket release clearance. A local
JAR hash identifies the exact bytes currently present in this checkout; it
does not by itself prove that a modified artifact has the upstream license.
The inventory was refreshed on 2026-09-30.

## Embedded artifacts

| Component and version | Local embedded artifact (relative to project root) | Local size | Local SHA-256 | License / terms | Upstream evidence and uncertainty |
| --- | --- | ---: | --- | --- | --- |
| Gradle wrapper bootstrap; project distribution URL says 9.6.1 | `gradle/wrapper/gradle-wrapper.jar` | 63,721 | `0336f591bc0ec9aa0c9988929b93ecc916b3c1d52aed202c7381db144aa0ef15` | Apache-2.0; see `GRADLE-9.6.1-LICENSE.txt`, `GRADLE-9.6.1-DISTRIBUTION-LICENSES.txt`, and `GRADLE-9.6.1-NOTICE.txt` | `gradle/wrapper/gradle-wrapper.properties` points at `https://services.gradle.org/distributions/gradle-9.6.1-bin.zip`; that distribution has SHA-256 `9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14`. The 9.6.1 distribution's nested wrapper JAR was retrieved and hashed as `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`, so the local 63,721-byte JAR is not byte-identical to that nested artifact. Its exact generating Gradle release is unresolved; retain the Gradle terms but do not claim binary identity. Source tag: `https://github.com/gradle/gradle/tree/v9.6.1` (tag commit `309d128bd9fe8c0b71311878fc660b9cbaa07c51`). |
| TeaVM core, upstream 0.13.1, Eagler-modified | `wasm-toolchain/teavm-eagler-patch/teavm-core-0.13.1-eagler.jar` | 2,957,256 | `c8cf14ce5da948fc76804a26b2e4fd8640169533774eed340ef854790e746672` | Upstream TeaVM Apache-2.0; see `TEAVM-0.13.1-LICENSE.txt` and `TEAVM-0.13.1-NOTICE.txt`. Local modifications are not separately cleared. | Upstream source tag `https://github.com/konsoletyper/teavm/tree/0.13.1` (commit `b3a245b7d9034ff35cdfab2def057a3d4f256efb`) and artifact `https://repo.maven.apache.org/maven2/org/teavm/teavm-core/0.13.1/teavm-core-0.13.1.jar` (upstream SHA-256 `b9fe1b008512c8f11ff3efbd9cf2f0565b4bfa76943a9f23910befc0812f7ebf`, 2,950,160 bytes). The local bytes differ. The accepted skeleton carries the modified JAR but not the source patch used to produce it; preserve that gap. |
| TeaVM JSO implementation, upstream 0.13.1, Eagler-modified | `wasm-toolchain/teavm-eagler-patch/teavm-jso-impl-0.13.1-eagler.jar` | 195,343 | `f71b62dc485ce714a8750f62000cd9c8995317acb502027ccdb292e951e3657c` | Upstream TeaVM Apache-2.0; see the TeaVM license and notice files. Local modifications are not separately cleared. | Upstream artifact `https://repo.maven.apache.org/maven2/org/teavm/teavm-jso-impl/0.13.1/teavm-jso-impl-0.13.1.jar` has SHA-256 `335778b447c10a8029d59a413c37b3b499f1c34ef9e25b08d11f2414a511df0a` (198,975 bytes). The local bytes differ. The patch source/provenance is not present in the accepted skeleton's patch directory, so the exact modified files remain unresolved. |
| TeaVM class-library URI overlay targeting 0.13.1 | `wasm-toolchain/teavm-eagler-patch/uri-classlib-override.jar` | 10,473 | `9bdfe2a9e35e563c9e7fcf8fe31400aad1de2f9555a026383fdc17f031a58c57` | Project overlay; no license file or upstream artifact identity is embedded. Do not automatically treat this local overlay as cleared Apache-2.0. | Contains `org.teavm.classlib.java.net.TURI` classes. No authoritative upstream artifact matching these bytes was found. Review the source that generated the overlay and its modification notices before redistribution. |
| TeaVM runtime Fiber overlay targeting 0.13.1 | `wasm-toolchain/teavm-eagler-patch/fiber-override.jar` | 8,603 | `2385812eef9da39a2ba3d8765d7c43d54570fcaea13c8ef7a21274f18c1cde64` | Project overlay; no license file or upstream artifact identity is embedded. Do not automatically treat this local overlay as cleared Apache-2.0. | Contains `org.teavm.runtime.Fiber` classes. No authoritative upstream artifact matching these bytes was found. Review source provenance and modification notices. |
| TeaVM class-library NIO/ZIP/stream overlays targeting 0.13.1 | `wasm-toolchain/teavm-eagler-patch/classlib-override.jar` | 18,473 | `0b1a0ffc04183a2431bdd249b32bf1639b020410b280fd14bf9b5783dd7622b0` | Project overlay; no license file or upstream artifact identity is embedded. Do not automatically treat this local overlay as cleared Apache-2.0. | Contains `TJSBufferHelper`, `TByteBufferImpl`, `TSimpleStreamImpl`, and `TZipFile` classes. No authoritative upstream artifact matching these bytes was found. Review source provenance and modification notices. |
| Paulscode SoundSystem, 20120107 | `platform-lwjgl/lib/soundsystem-20120107.jar` | 65,020 | `2882d64550240dd0c026724da664d9f97ef205c91d6a85273d10790d88608f34` | Paulscode SoundSystem terms; see `PAULSCODE-SOUNDSYSTEM-LICENSE.txt`. | Local bytes exactly match the artifact retrieved from `https://repo.spongepowered.org/maven/com/paulscode/soundsystem/20120107/soundsystem-20120107.jar` and `https://libraries.minecraft.net/com/paulscode/soundsystem/20120107/soundsystem-20120107.jar` (both SHA-256 above). The embedded POM names `http://www.paulscode.com` as the project URL. The license text is preserved from the MinecraftForge archival mirror at commit `2ba608bbf69b9e82b4004cb83d24c09da8f23b3b`; the original site is historical and not treated as a current verification endpoint. |
| Paulscode CodecWav, 20101023 | `platform-lwjgl/lib/codecwav-20101023.jar` | 5,618 | `bb7d17b340afe6abdfbfdaa03683bce4aef39a64887dbab0636eaff3cf2d59ba` | Paulscode CodecWav terms; see `PAULSCODE-CODECWAV-LICENSE.txt`. | Local bytes exactly match `https://repo.spongepowered.org/maven/com/paulscode/codecwav/20101023/codecwav-20101023.jar` and `https://libraries.minecraft.net/com/paulscode/codecwav/20101023/codecwav-20101023.jar` (SHA-256 above). Embedded POM URL is `http://www.paulscode.com`. License text is from the archival Paulscode source mirror at `https://github.com/kovertopz/Paulscode-SoundSystem/tree/e4871ecb024549bc04a68d27acd6a3a18edce22f`; mirror status is noted rather than presented as an official current Paulscode repository. |
| Paulscode CodecJOrbis, 20101023, containing JCraft JOrbis/jogg classes | `platform-lwjgl/lib/codecjorbis-20101023.jar` | 103,871 | `6c4b4e50e608763564afa1bde2d25ece9dd715e7c9129540faa1faded4896506` | Paulscode CodecJOrbis class terms plus the JCraft source-header grant: GNU Library General Public License version 2, or any later version; see `PAULSCODE-CODECJORBIS-LICENSE.txt`, `JCRAFT-JORBIS-LICENSE-HEADER.txt`, and `JCRAFT-COPYING.LIB.txt`. | Local bytes exactly match `https://repo.spongepowered.org/maven/com/paulscode/codecjorbis/20101023/codecjorbis-20101023.jar` and `https://libraries.minecraft.net/com/paulscode/codecjorbis/20101023/codecjorbis-20101023.jar` (SHA-256 above). The JAR contains `com.jcraft.jorbis.*`, `com.jcraft.jogg.*`, and `paulscode.sound.codecs.CodecJOrbis`. Its POM does not declare the nested JOrbis version. `org.jcraft:jorbis:0.0.17` (POM/source reference: `https://repo.maven.apache.org/maven2/org/jcraft/jorbis/0.0.17/`) is a provenance reference, not a byte-identity claim for the nested classes. |
| UnsafeMemcpy desktop helper | `platform-lwjgl/lib/UnsafeMemcpy.jar` | 5,842 | `0e04b5178fb38209c792f8eb14beeb27aca6441d6f35cf0f5ff0da2803ca9edc` | **UNRESOLVED.** No authoritative source, license, or grant was identified. | This item is explicitly not cleared. Do not remove it or claim it is redistributable based on this inventory. Its native companion is also unresolved: `target_lwjgl_desktop/natives/libUnsafeMemcpy.so`, 127,880 bytes, SHA-256 `e5b685d2eb404351544c9fb7a05f2d489d9817885ab78e96fd87ab50b1e9f2fd`. Its presence is a release blocker for any package that carries the desktop LWJGL path until source and rights are established. |
| UnsafeMemcpy native companion | `target_lwjgl_desktop/natives/libUnsafeMemcpy.so` | 127,880 | `e5b685d2eb404351544c9fb7a05f2d489d9817885ab78e96fd87ab50b1e9f2fd` | **UNRESOLVED.** No authoritative source, license, or grant was identified. | This native binary is explicitly not cleared. Its source and redistribution rights must be resolved together with `platform-lwjgl/lib/UnsafeMemcpy.jar`. |

## Notice-text provenance

The copied text files in this directory are exact upstream text snapshots, not
rewritten summaries:

- `GRADLE-9.6.1-LICENSE.txt` is the exact Apache/Gradle source-tag license
  text at `https://raw.githubusercontent.com/gradle/gradle/v9.6.1/LICENSE`
  (SHA-256 `0d542e0c8804e39aa7f37eb00da5a762149dc682d7829451287e11b938e94594`).
  `GRADLE-9.6.1-DISTRIBUTION-LICENSES.txt` and
  `GRADLE-9.6.1-NOTICE.txt` came from the Gradle 9.6.1 binary distribution at
  `https://services.gradle.org/distributions/gradle-9.6.1-bin.zip` (the ZIP
  SHA-256 is recorded above). Their extracted text hashes are
  `bb36a3818cbf1f90a76585ab9e9661cccde459c97458c4bfd0939e4502353fd0` and
  `c2de5fd9adc7ccc4e1382a0e1f38ec6e14f8ca6e28d3c20289fe80353b019aa1`.
- `TEAVM-0.13.1-LICENSE.txt` and `TEAVM-0.13.1-NOTICE.txt` came from the
  TeaVM 0.13.1 source tag. Their SHA-256 hashes are
  `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30` and
  `6c2b3372a6beee7fbaad482248ac0f5ad2b099d92ff2a528c04261cc9c0636f4`
  respectively at the source URL. The checked-in NOTICE snapshot has one
  final newline added by the patch tool (local hash
  `1c56b3648f3ff043c88ca5fe5595ee6827f75ebb26a82bf496e60b7e4cc76d4b`); its
  notice wording is unchanged.
- `PAULSCODE-SOUNDSYSTEM-LICENSE.txt` is also byte-identical to the
  MinecraftForge archival copy. The CodecWav and CodecJOrbis text is copied
  from the pinned Paulscode source mirror listed in the table; no license
  wording has been invented here. Its downloaded text hashes are:
  SoundSystem `8bd42148ed784bf5dbc3e38747377d494c1baaaf28fadc74df299715233b0fb9`,
  CodecWav `bad4ba9c3fee41222fc2706153503bb737e990ae37986bb5dcb52396c0ce4ddd`.
  The CodecJOrbis class terms are copied from the class source at the same
  pinned commit and are preserved in `PAULSCODE-CODECJORBIS-LICENSE.txt`
  (local snapshot hash `9d323658e016c414aa6861ff558e098f7e327bacaf06c26d523069eb91761071`).
- `JCRAFT-JORBIS-LICENSE-HEADER.txt` contains the exact header used by both
  `com.jcraft.jorbis.*` and `com.jcraft.jogg.*` source files in the downloaded
  JOrbis sources. The pinned JCraft repository is
  `https://github.com/ymnk/jorbis/tree/28592f3dde5134871165470e7390d66ed5f1dce5`;
  the header snapshot hash is
  `55786f79effcfbcfa3ae0e9457186367eb46547735d200b1be2b60c290ba0c99`.
  The header says “GNU Library General Public License ... version 2 ... or
  any later version”; do not narrow this to a single later license version.
- `JCRAFT-COPYING.LIB.txt` is the full `COPYING.LIB` text from that pinned
  JCraft repository (SHA-256
  `5bbcbb737e60fe9deba08ecbd00920cfcc3403ba2e534c64fdeea49d6bb87509`).
  It is the LGPL 2.1 text, which is the successor to the GNU Library Public
  License version 2 and matches the source header's v2-or-later wording.
- For the nested JOrbis provenance reference, the downloaded Maven Central
  `org.jcraft:jorbis:0.0.17` POM hash was
  `18de3b0d932af99832db4d832f683507fbdd5a0b0c24dd680e84ce6f57182e24` and
  its source JAR hash was
  `1643dd368b9c160276caf8d1f6a8c0aae43ca5bf49b53348a2a01623641708e5`.
  These hashes document the reference download only; they do not prove that
  the nested classes in CodecJOrbis were built from exactly that release.

## Remaining legal/provenance work

The notice set is intentionally conservative. Before public redistribution,
retain source/patch lineage for both Eagler-modified TeaVM JARs, identify the
license for each local TeaVM overlay, determine the exact nested JCraft JOrbis
release inside `codecjorbis`, and resolve both `UnsafeMemcpy.jar` and
`libUnsafeMemcpy.so`. Until then, this is a
source-side notice package and not a completed release gate.

The JOrbis source headers identify copyright `(C) 2000 ymnk, JCraft, Inc.` and
state that JOrbis is based on the Vorbis work from Xiph. The JCraft source
header and full LGPL 2.1 successor terms are retained verbatim in the separate text
files; those attributions and terms must remain with any redistribution.
