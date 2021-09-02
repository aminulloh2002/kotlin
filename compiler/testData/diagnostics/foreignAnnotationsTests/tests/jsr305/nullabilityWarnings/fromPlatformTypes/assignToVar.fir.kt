// JSR305_GLOBAL_REPORT: warn

// FILE: J.java
public class J {
    @MyNonnull
    public static J staticNN;
    @MyNullable
    public static J staticN;
    public static J staticJ;
}

// FILE: k.kt
var v: J = J()
var n: J? = J()

fun test() {
    v = J.staticNN
    v = J.staticN
    v = J.staticJ

    n = J.staticNN
    n = J.staticN
    n = J.staticJ
}
