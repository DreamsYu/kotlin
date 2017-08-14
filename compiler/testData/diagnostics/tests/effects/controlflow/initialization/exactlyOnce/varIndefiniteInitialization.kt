// !DIAGNOSTICS: -INVISIBLE_MEMBER -INVISIBLE_REFERENCE

import kotlin.internal.*

fun myRun(@CalledInPlace(InvocationCount.EXACTLY_ONCE) block: () -> Unit) = block()

fun indefiniteVarReassignment(n: Int) {
    var x: Int
    repeat(n) {
        myRun { x = 42 }
    }
    <!UNINITIALIZED_VARIABLE!>x<!>.inc()
}

fun nonAnonymousLambdas() {
    // Named lambdas are not inlined, even in theory it could be done for some simple cases as this one
    var x: Int
    val initializer = { x = 42 }
    myRun(initializer)
    <!UNINITIALIZED_VARIABLE!>x<!>.inc()
}

fun branchingIndetermineFlow(a: Any) {
    var x: Int

    if (a is String) {
        repeat(<!DEBUG_INFO_SMARTCAST!>a<!>.length) {
            myRun { x = 42 }
        }
    } else {
        myRun { x = 43 }
    }

    <!UNINITIALIZED_VARIABLE!>x<!>.inc()
}

fun funWithUnknownInvocations(block: () -> Unit) = block()

fun nestedIndefiniteAssignment() {
    val x: Int
    funWithUnknownInvocations { myRun { <!CAPTURED_VAL_INITIALIZATION!>x<!> = 42 } }
    <!UNINITIALIZED_VARIABLE!>x<!>.inc()
}