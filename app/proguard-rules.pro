# Stage 1: shrink only, no renaming (near-zero JNI risk). Obfuscation deferred to Stage 2.
-dontobfuscate

# R8 9 (AGP 9.0+) defaults `-processkotlinnullchecks remove_message`, which rewrites every
# kotlin.jvm.internal.Intrinsics null-check call site and then shrinks the unreferenced
# checkNotNull* methods out of the APK. The separately compiled release AndroidTest APK
# still calls those methods in this APK at runtime (device-only NoSuchMethodError), and
# tools/r8_check/check_release_dex.sh gates them. Keep the AGP 8 behavior: the checks
# stay as compiled, messages included.
-processkotlinnullchecks keep

# ---- Native (name-based JNI) boundary: all four *_jni.cpp bind via exported
# Java_<fqcn>_<method> symbols (no RegisterNatives) and resolve classes/methods/ctors
# by literal string from C++. Keep these un-renamed and un-removed. ----
-keep class com.argentroom.engine.** { *; }
-keep class com.argentroom.libraw.RawDecoder { *; }
-keep class com.argentroom.libraw.RawDecoder$NativeResult { *; }
-keep class com.argentroom.libraw.RawDecodeException { *; }
# Ticket #158's separately packaged release probe hashes the production
# malloc-backed result through this public lease ABI. R8 cannot see that
# cross-APK call edge, so retain the exact method/class names it invokes.
-keep class com.argentroom.libraw.LinearResult { *; }
-keep class com.argentroom.libraw.LinearResult$DataLease { *; }
-keep class com.argentroom.tiffwriter.TiffWriter { *; }
-keep class com.argentroom.pngwriter.PngWriter { *; }
-keep class com.argentroom.tiffwriter.TiffCancellationToken { *; }
-keep class com.argentroom.pngwriter.PngCancellationToken { *; }
-keepclasseswithmembernames class * { native <methods>; }
# LiteRT (on-device skin finder): its native runtime looks up Kotlin/Java classes, fields and
# methods by name, which R8 cannot see. Keep the whole runtime package rather than trusting its
# consumer rules -- a wrong guess here fails silently (skin masks just stay empty), see docs/MAINTAINERS.md.
-keep class com.google.ai.edge.litert.** { *; }
# Its Play AI-pack delivery classes reference the Play libraries excluded in build.gradle.kts;
# that path is never called here (the model comes from SkinModelStore).
-dontwarn com.google.android.play.**
-dontwarn com.google.android.gms.**

# ---- Release-candidate instrumentation boundary ----
# The separately packaged release AndroidTest APK exercises process-death recovery,
# failure cleanup, durable URI grants, and duplicate-name MediaStore publication
# against the exact minified app. These package-private Kotlin types are referenced
# across the APK boundary, so R8 must not merge/remove them from the target APK.
-keep class com.argentroom.app.export.EncodedArtifact { *; }
-keep class com.argentroom.app.export.EncodedArtifact$Companion { *; }
-keep class com.argentroom.app.export.ExportDestinationSpec { *; }
-keep class com.argentroom.app.export.ExportTransaction { *; }
-keep class com.argentroom.app.export.ExportRecoveryReport { *; }
-keep class com.argentroom.app.export.AndroidPendingExportBackend { *; }
-keep class com.argentroom.app.export.SharedPreferencesPendingExportJournal { *; }
-keep class com.argentroom.app.export.StoredExportState { *; }
-keep interface com.argentroom.app.export.PendingExportBackend { *; }
-keep interface com.argentroom.app.export.PendingExportJournal { *; }
-keep class com.argentroom.app.export.ExportTransactionKt { *; }
-keep class com.argentroom.app.source.PersistedSourceRef { *; }
-keep class com.argentroom.app.source.SourceAccessMode { *; }
-keep class com.argentroom.app.source.SourceAccessCoordinator { *; }
-keep class com.argentroom.app.source.SourceRestoreResult { *; }
-keep class com.argentroom.app.source.SourceRestoreResult$* { *; }
-keep class com.argentroom.app.source.SharedPreferencesSourceRefStore { *; }
-keep interface com.argentroom.app.source.SourceRefStore { *; }
-keep interface com.argentroom.app.source.UriGrantBackend { *; }
-keep class com.argentroom.app.source.AndroidUriGrantBackend { *; }
-keep class com.argentroom.app.look.Recipes { *; }

# Process-owned native/export probes are also compiled into the separate test
# APK. Keep their exact target-APK ABI: otherwise R8 may inline/staticize object
# methods, remove data-class accessors, or delete Kotlin interface DefaultImpls
# even though the instrumentation still invokes their original JVM descriptors.
-keep class com.argentroom.app.render.EngineHolder { *; }
-keep class com.argentroom.app.export.ExportWorkRuntime { *; }
-keep class com.argentroom.app.export.ExportRuntimeState { *; }
-keep class com.argentroom.app.export.ExportRuntimeState$* { *; }
-keep class com.argentroom.app.export.ExportTerminalOutcome { *; }
-keep class com.argentroom.app.export.ExportTerminalOutcome$* { *; }
-keep class com.argentroom.app.export.ExportPhaseSnapshot { *; }
-keep class com.argentroom.app.export.ExportFormat { *; }
-keep class com.argentroom.app.export.PendingExportBackend$DefaultImpls { *; }

# Ticket #139 deliberately exposes one narrow, fixed-fixture bridge instead of making the entire
# editor-session/store/source implementation a cross-APK ABI. The release test APK calls only these
# public methods; their bodies keep the app-internal implementation edges visible to target R8.
-keep class com.argentroom.app.diagnostics.Ticket139EditorTestBridge { public *; }

# Ticket #181's accessibility scan (same release test APK) walks every editor category by its
# tab label, so the enum's values()/getLabelRes() are cross-APK ABI. String ids are resolved by
# name (getIdentifier) on purpose: AGP 8 keeps R ids non-final and R8 drops R$string entirely.
-keep class com.argentroom.app.editor.Category { *; }

# The #139 Activity-recreation probe binds its export's source identity from the release test APK
# (StorageReliabilityChecks), so the authority and the value types that cross with it are cross-APK
# ABI exactly like ExportWorkRuntime above. Without these, R8 class-inlines the singleton (its only
# in-app callers are direct) and the test APK dies with NoSuchFieldError: INSTANCE at runtime.
-keep class com.argentroom.app.export.ExportSourceIdentityAuthority { *; }
-keep class com.argentroom.app.export.ExportSourceIdentity { *; }
-keep class com.argentroom.app.export.ExportSourceIdentity$* { *; }
-keep class com.argentroom.app.source.SourceKind { *; }

# Ticket #174's separate release test APK calls the production descriptor/color contract and the
# production float-to-tagged-Bitmap bridge, then parses the encoded files on a physical device.
-keep class com.argentroom.app.export.OutputDescriptor { *; }
-keep class com.argentroom.app.export.OutputDescriptor$Companion { *; }
-keep class com.argentroom.app.export.OutputMetadataPolicy { *; }
-keep class com.argentroom.app.export.OutputBitDepth { *; }
-keep class com.argentroom.app.export.OutputEncoder { *; }
-keep class com.argentroom.app.export.OutputReleaseStatus { *; }
-keep class com.argentroom.app.export.ExportOptions { *; }
-keep class com.argentroom.app.export.ExportSize { *; }
-keep class com.argentroom.app.export.ColorManagement { *; }
# The same check calls the 3-argument simResultToBitmap (the synthetic default-argument bridge)
# and packToArgb as its reference packer. The app never calls either, so R8 removed them and
# the v1.0.0 candidate died with NoSuchMethodError: packToArgb([F[II)V.
-keepclassmembers class com.argentroom.app.render.DisplayBitmapsKt {
    public static android.graphics.Bitmap simResultToBitmap(...);
    public static android.graphics.Bitmap simResultToBitmap$default(...);
    public static void packToArgb(float[], int[], int);
}

# Ticket #177's benchmark phase (same release test APK) measures the SHIPPING export path:
# it calls the production decode, params, engine, grade and encoder entry points on the
# minified app rather than re-implementing them, so those are cross-APK ABI too.
-keep class com.argentroom.app.render.ImagePipelineKt { *; }
# #273 split the export writers and EXIF copy out of ImagePipeline.kt; their top-level
# functions now compile into these file classes, which the same release test APK calls.
-keep class com.argentroom.app.export.GalleryExportKt { *; }
-keep class com.argentroom.app.export.ExportExifKt { *; }
# #198: the bench passes decodeToLinearProPhoto a DecodeTimings sink that no app code
# constructs, so without this R8 deletes its constructor (InstantiationError in the test APK).
-keep class com.argentroom.app.render.DecodeTimings { *; }
-keep class com.argentroom.app.look.ParamsState { *; }
-keep class com.argentroom.app.look.BuiltInPresets { *; }
-keep class com.argentroom.app.look.BuiltInPreset { *; }
-keep class com.argentroom.app.look.ColorGrade { *; }
-keep class com.argentroom.app.export.ExportClock { *; }
# #179/#140: the benchmark drives the shipping export cache and the render-derived gain map
# across the APK boundary. The trailing ** matters -- ExportCacheKey.Source/Grade are nested
# types and compile to separate classes that a bare keep would not cover.
-keep class com.argentroom.app.export.ExportCache** { *; }
-keep class com.argentroom.app.export.HdrGainMap** { *; }
# Same boundary for the pre-render payload store: the app only ever constructs it with default
# arguments, so R8 rewrote the constructor the (unminified) instrumentation calls and the check
# died with NoSuchMethodError on a device.
-keep class com.argentroom.app.render.RenderPayloadCache** { *; }
-keep class com.argentroom.app.render.RenderPayloads** { *; }
-keep class com.argentroom.app.masks.MaskCompositor { *; }
-keepclassmembers class com.argentroom.app.render.DisplayBitmapsKt {
    public static android.graphics.Bitmap simResultToBitmapGraded(...);
}

# Kotlin inline functions in the separately packaged release AndroidTest APK
# still emit calls to Result's JVM implementation ABI. R8 cannot see those call
# edges while shrinking the target APK, so it may remove Result.Companion or an
# *-impl method and leave a device-only NoSuchFieldError/NoSuchMethodError.
# Keep this small stdlib ABI surface intact and verify the physical dex below.
-keep class kotlin.Result { *; }
-keep class kotlin.Result$Companion { *; }
-keep class kotlin.Result$Failure { *; }
-keep class kotlin.ResultKt { *; }

# The release AndroidTest APK intentionally does not package a second Kotlin or
# coroutines runtime. Its lowered suspend/use/loop code resolves these bounded
# facades and interfaces from the target APK. Preserve only the families/types
# proven by the final test dex; do not replace this with kotlin.**/kotlinx.**.
# The locked stdlib's Collections/Intrinsics/Ranges facades are empty subclasses;
# the locked coroutines Builders/Job/Flow facades are forwarding classes. Keep
# each public owner plus its exact declaring/delegate part without retaining the
# unrelated members of either runtime family.
-keep class kotlin.KotlinNothingValueException { *; }
-keep class kotlin.collections.CollectionsKt
-keep class kotlin.collections.CollectionsKt__IterablesKt {
    public static int collectionSizeOrDefault(java.lang.Iterable, int);
}
-keep class kotlin.collections.CollectionsKt__CollectionsKt {
    public static void throwCountOverflow();
}
-keep class kotlin.collections.IntIterator { *; }
-keep class kotlin.coroutines.intrinsics.IntrinsicsKt
-keep class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsKt {
    public static java.lang.Object getCOROUTINE_SUSPENDED();
}
-keep class kotlin.coroutines.jvm.internal.Boxing { *; }
# Kotlin 2.2 (AGP 9 built-in Kotlin) compiles every suspend function to null out spilled
# locals through this stdlib helper. The release test APK's suspend lambdas resolve it
# from this APK, and R8 otherwise inlines the trivial body and drops the class
# (device-only ClassNotFoundException: kotlin.coroutines.jvm.internal.SpillingKt).
-keep class kotlin.coroutines.jvm.internal.SpillingKt { *; }
-keep class kotlin.jdk7.AutoCloseableKt { *; }
-keep class kotlin.io.CloseableKt {
    public static void closeFinally(java.io.Closeable, java.lang.Throwable);
}
# A `var` captured by a lambda in the release test APK (Ticket141MaskMemoryChecks) boxes
# through Ref$ObjectRef, which the app APK otherwise never instantiates -- a device-only
# NoClassDefFoundError that no build step can see.
-keep class kotlin.jvm.internal.Ref$ObjectRef { *; }
-keep class kotlin.ranges.RangesKt
-keep class kotlin.ranges.RangesKt___RangesKt {
    public static kotlin.ranges.IntRange until(int, int);
    public static long coerceAtLeast(long, long);
}
-keep class kotlin.ranges.IntRange { *; }
-keep class kotlin.ranges.IntProgression {
    public java.util.Iterator iterator();
}
-keep class kotlin.ranges.IntProgressionIterator {
    public boolean hasNext();
    public int nextInt();
}
-keep class kotlinx.coroutines.AwaitKt { *; }
-keep class kotlinx.coroutines.BuildersKt {
    public static java.lang.Object runBlocking$default(kotlin.coroutines.CoroutineContext, kotlin.jvm.functions.Function2, int, java.lang.Object);
    public static kotlinx.coroutines.Job launch$default(kotlinx.coroutines.CoroutineScope, kotlin.coroutines.CoroutineContext, kotlinx.coroutines.CoroutineStart, kotlin.jvm.functions.Function2, int, java.lang.Object);
    public static kotlinx.coroutines.Deferred async$default(kotlinx.coroutines.CoroutineScope, kotlin.coroutines.CoroutineContext, kotlinx.coroutines.CoroutineStart, kotlin.jvm.functions.Function2, int, java.lang.Object);
}
-keep class kotlinx.coroutines.BuildersKt__BuildersKt {
    public static java.lang.Object runBlocking$default(kotlin.coroutines.CoroutineContext, kotlin.jvm.functions.Function2, int, java.lang.Object);
}
-keep class kotlinx.coroutines.BuildersKt__Builders_commonKt {
    public static kotlinx.coroutines.Job launch$default(kotlinx.coroutines.CoroutineScope, kotlin.coroutines.CoroutineContext, kotlinx.coroutines.CoroutineStart, kotlin.jvm.functions.Function2, int, java.lang.Object);
    public static kotlinx.coroutines.Deferred async$default(kotlinx.coroutines.CoroutineScope, kotlin.coroutines.CoroutineContext, kotlinx.coroutines.CoroutineStart, kotlin.jvm.functions.Function2, int, java.lang.Object);
}
-keep interface kotlinx.coroutines.CompletableDeferred { *; }
-keep class kotlinx.coroutines.CompletableDeferredKt { *; }
-keep interface kotlinx.coroutines.CoroutineScope { *; }
-keep class kotlinx.coroutines.CoroutineScopeKt { *; }
-keep interface kotlinx.coroutines.Deferred { *; }
-keep class kotlinx.coroutines.DelayKt { *; }
-keep class kotlinx.coroutines.Dispatchers { *; }
-keep interface kotlinx.coroutines.Job { *; }
-keep class kotlinx.coroutines.JobKt {
    public static java.lang.Object cancelAndJoin(kotlinx.coroutines.Job, kotlin.coroutines.Continuation);
}
-keep class kotlinx.coroutines.JobKt__JobKt {
    public static java.lang.Object cancelAndJoin(kotlinx.coroutines.Job, kotlin.coroutines.Continuation);
}
-keep class kotlinx.coroutines.TimeoutKt { *; }
-keep class kotlinx.coroutines.CoroutineDispatcher { *; }
-keep class kotlinx.coroutines.CoroutineStart { *; }
-keep class kotlinx.coroutines.flow.FlowKt {
    public static java.lang.Object first(kotlinx.coroutines.flow.Flow, kotlin.coroutines.Continuation);
}
-keep class kotlinx.coroutines.flow.FlowKt__ReduceKt {
    public static java.lang.Object first(kotlinx.coroutines.flow.Flow, kotlin.coroutines.Continuation);
}
-keep interface kotlinx.coroutines.flow.Flow { *; }
-keep interface kotlinx.coroutines.flow.FlowCollector { *; }
-keep interface kotlinx.coroutines.flow.StateFlow { *; }

# ---- kotlin.Triple / kotlin.Pair are ALSO part of that JNI boundary ----
# spektra_jni.cpp reads 19 engine params out of Triple<Float,Float,Float> and
# Pair<Float,Float> by resolving getFirst/getSecond/getThird from C++ by literal
# string. No BYTECODE calls those getters, so R8 sees them as unreachable and
# REMOVES them -- and `-dontobfuscate` does not help, because it prevents
# renaming, not removal.
#
# The result shipped: every Triple/Pair param (grain particle scale, density min,
# uniformity, halation scatter/strength, the four coupler gammas, camera UV/IR,
# scanner unsharp, crop centre and size) marshalled as 0.0 in every release APK,
# with no crash and no log. Zero grain particle scale is degenerate, which is why
# the slide route rendered a flat constant.
#
# spektra_jni.cpp now falls back to the backing FIELDS (which R8 keeps) and no
# longer overwrites defaults on failure, so this rule is belt-and-braces rather
# than the only thing standing between us and wrong numbers. Keep both.
# tools/r8_check/check_release_dex.sh gates it.
-keep class kotlin.Triple { *; }
-keep class kotlin.Pair { *; }

# ---- Enum name<->value persistence (prefs/presets/Serializable saver) ----
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}
