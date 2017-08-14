// !DIAGNOSTICS: -INVISIBLE_MEMBER -INVISIBLE_REFERENCE

import kotlin.internal.*

@Returns(ConstantValue.TRUE)
fun isString(@IsInstance(String::class) x: Any?) = x is String

fun testEqualsWithConstant(x: Any?) {
    if (isString(x) == true) {
        <!DEBUG_INFO_SMARTCAST!>x<!>.length
    } else {
        x.<!UNRESOLVED_REFERENCE!>length<!>
    }
}

fun testNotEqualsWithConstant(x: Any?) {
    if (isString(x) != true) {
        x.<!UNRESOLVED_REFERENCE!>length<!>
    } else {
        <!DEBUG_INFO_SMARTCAST!>x<!>.length
    }
}

fun unknownFunction(): Any? = 42

fun testEqualsWithUnknown(x: Any?) {
    if (isString(x) == unknownFunction()) {
        x.<!UNRESOLVED_REFERENCE!>length<!>
    } else {
        x.<!UNRESOLVED_REFERENCE!>length<!>
    }
    x.<!UNRESOLVED_REFERENCE!>length<!>
}

fun testNotEqualsWithUnknown(x: Any?) {
    if (isString(x) != unknownFunction()) {
        x.<!UNRESOLVED_REFERENCE!>length<!>
    } else {
        x.<!UNRESOLVED_REFERENCE!>length<!>
    }
    x.<!UNRESOLVED_REFERENCE!>length<!>
}

fun testEqualsWithVariable(x: Any?, b: Boolean) {
    if (isString(x) == b) {
        x.<!UNRESOLVED_REFERENCE!>length<!>
    } else {
        x.<!UNRESOLVED_REFERENCE!>length<!>
    }
}

fun testNotEqualsWithVariable(x: Any?, b: Boolean) {
    if (isString(x) != b) {
        x.<!UNRESOLVED_REFERENCE!>length<!>
    } else {
        x.<!UNRESOLVED_REFERENCE!>length<!>
    }
}