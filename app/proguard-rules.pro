# LSPosed loads this class by the literal name in META-INF/xposed/java_init.list.
# Its public callbacks are invoked by the external libxposed runtime.
-keep class io.github.selectionmenucontrol.SelectionMenuModule { public *; }

# AndroidViewModelFactory instantiates these constructors through reflection.
-keepclassmembers class io.github.selectionmenucontrol.** extends androidx.lifecycle.AndroidViewModel {
    public <init>(android.app.Application);
}

# SystemMenuClassifier reflects only Android/ColorOS framework classes, which
# are supplied by the device and are not processed by R8. No package-wide keep
# is needed for app helpers referenced directly from the module.
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,AnnotationDefault
