// A handful of classes and functions to support the generated data structures.
// This would be a good candidate for isolating in its own ffi-support lib.
// Error runtime.
@Structure.FieldOrder("code", "error_buf")
internal open class UniffiRustCallStatus : Structure() {
    @JvmField var code: Byte = 0
    @JvmField var error_buf: RustBuffer.ByValue = RustBuffer.ByValue()

    class ByValue: UniffiRustCallStatus(), Structure.ByValue

    fun isSuccess(): Boolean {
        return code == 0.toByte()
    }

    fun isError(): Boolean {
        return code == 1.toByte()
    }

    fun isPanic(): Boolean {
        return code == 2.toByte()
    }
}

class InternalException(message: String) : Exception(message)

// Each top-level error class has a companion object that can lift the error from the call status's rust buffer
interface UniffiRustCallStatusErrorHandler<E> {
    fun lift(error_buf: RustBuffer.ByValue): E;
}

// Helpers for calling Rust
// In practice we usually need to be synchronized to call this safely, so it doesn't
// synchronize itself

// Call a rust function that returns a Result<>.  Pass in the Error class companion that corresponds to the Err
private inline fun <U, E: Exception> uniffiRustCallWithError(errorHandler: UniffiRustCallStatusErrorHandler<E>, callback: (UniffiRustCallStatus) -> U): U {
    var status = UniffiRustCallStatus();
    val return_value = callback(status)
    uniffiCheckCallStatus(errorHandler, status)
    return return_value
}

// Check UniffiRustCallStatus and throw an error if the call wasn't successful
private fun<E: Exception> uniffiCheckCallStatus(errorHandler: UniffiRustCallStatusErrorHandler<E>, status: UniffiRustCallStatus) {
    if (status.isSuccess()) {
        return
    } else if (status.isError()) {
        throw errorHandler.lift(status.error_buf)
    } else if (status.isPanic()) {
        // when the rust code sees a panic, it tries to construct a rustbuffer
        // with the message.  but if that code panics, then it just sends back
        // an empty buffer.
        if (status.error_buf.len > 0) {
            throw InternalException({{ Type::String.borrow()|lift_fn }}(status.error_buf))
        } else {
            throw InternalException("Rust panic")
        }
    } else {
        throw InternalException("Unknown rust call status: $status.code")
    }
}

// UniffiRustCallStatusErrorHandler implementation for times when we don't expect a CALL_ERROR
object UniffiNullRustCallStatusErrorHandler: UniffiRustCallStatusErrorHandler<InternalException> {
    override fun lift(error_buf: RustBuffer.ByValue): InternalException {
        RustBuffer.free(error_buf)
        return InternalException("Unexpected CALL_ERROR")
    }
}

// Call a rust function that returns a plain value
private inline fun <U> uniffiRustCall(callback: (UniffiRustCallStatus) -> U): U {
    return uniffiRustCallWithError(UniffiNullRustCallStatusErrorHandler, callback);
}

// IntegerType that matches Rust's `usize` / C's `size_t`
public class USize(value: Long = 0) : IntegerType(Native.SIZE_T_SIZE, value, true) {
    // This is needed to fill in the gaps of IntegerType's implementation of Number for Kotlin.
    override fun toByte() = toInt().toByte()
    // Needed until https://youtrack.jetbrains.com/issue/KT-47902 is fixed.
    @Deprecated("`toInt().toChar()` is deprecated")
    override fun toChar() = toInt().toChar()
    override fun toShort() = toInt().toShort()

    fun writeToBuffer(buf: ByteBuffer) {
        // Make sure we always write usize integers using native byte-order, since they may be
        // casted to pointer values
        buf.order(ByteOrder.nativeOrder())
        try {
            when (Native.SIZE_T_SIZE) {
                4 -> buf.putInt(toInt())
                8 -> buf.putLong(toLong())
                else -> throw RuntimeException("Invalid SIZE_T_SIZE: ${Native.SIZE_T_SIZE}")
            }
        } finally {
            buf.order(ByteOrder.BIG_ENDIAN)
        }
    }

    companion object {
        val size: Int
            get() = Native.SIZE_T_SIZE

        fun readFromBuffer(buf: ByteBuffer) : USize {
            // Make sure we always read usize integers using native byte-order, since they may be
            // casted from pointer values
            buf.order(ByteOrder.nativeOrder())
            try {
                return when (Native.SIZE_T_SIZE) {
                    4 -> USize(buf.getInt().toLong())
                    8 -> USize(buf.getLong())
                    else -> throw RuntimeException("Invalid SIZE_T_SIZE: ${Native.SIZE_T_SIZE}")
                }
            } finally {
                buf.order(ByteOrder.BIG_ENDIAN)
            }
        }
    }
}


// Map handles to objects
//
// This is used when the Rust code expects an opaque pointer to represent some foreign object.
// Normally we would pass a pointer to the object, but JNA doesn't support getting a pointer from an
// object reference , nor does it support leaking a reference to Rust.
//
// Instead, this class maps USize values to objects so that we can pass a pointer-sized type to
// Rust when it needs an opaque pointer.
//
// TODO: refactor callbacks to use this class
internal class UniFfiHandleMap<T: Any> {
    private val map = ConcurrentHashMap<USize, T>()
    // Use AtomicInteger for our counter, since we may be on a 32-bit system.  4 billion possible
    // values seems like enough. If somehow we generate 4 billion handles, then this will wrap
    // around back to zero and we can assume the first handle generated will have been dropped by
    // then.
    private val counter = java.util.concurrent.atomic.AtomicInteger(0)

    val size: Int
        get() = map.size

    fun insert(obj: T): USize {
        val handle = USize(counter.getAndAdd(1).toLong())
        map.put(handle, obj)
        return handle
    }

    fun get(handle: USize): T? {
        return map.get(handle)
    }

    fun remove(handle: USize): T? {
        return map.remove(handle)
    }
}

// FFI type for Rust future continuations
internal interface UniFffiRustFutureContinuationCallbackType : com.sun.jna.Callback {
    fun callback(continuationHandle: USize, pollResult: Byte);
}
