/**
 * PC Remote server module.
 *
 * <p>Requires only {@code java.desktop} (for {@link java.awt.Robot}, Swing and
 * image rendering) on top of the implicit {@code java.base}. Keeping the require
 * list this short is what lets jlink build a ~50 MB runtime instead of shipping
 * a full ~300 MB JDK. Add a module here only when you truly need it.
 */
module com.easyconnect.pcserver {
    requires java.desktop;

    exports com.easyconnect.pcserver;
}
