package com.splitfree.ui.util

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * A user-facing message produced by a ViewModel without holding a `Context`.
 *
 * [Res] carries a string resource id (plus format arguments) and [Plural] a plurals resource id with
 * its quantity; both are resolved in the composable with [asString]. [Raw] carries text that already
 * exists as a string, typically an exception message that originated in the domain layer and is
 * shown as-is.
 */
sealed interface UiMessage {
    class Res(@StringRes val id: Int, vararg val args: Any) : UiMessage {
        override fun equals(other: Any?): Boolean = other is Res && id == other.id && args.contentEquals(other.args)

        override fun hashCode(): Int = 31 * id + args.contentHashCode()

        override fun toString(): String = "Res(id=$id, args=${args.contentToString()})"
    }

    class Plural(@PluralsRes val id: Int, val count: Int, vararg val args: Any) : UiMessage {
        override fun equals(other: Any?): Boolean =
            other is Plural && id == other.id && count == other.count && args.contentEquals(other.args)

        override fun hashCode(): Int = (31 * id + count) * 31 + args.contentHashCode()

        override fun toString(): String = "Plural(id=$id, count=$count, args=${args.contentToString()})"
    }

    data class Raw(val text: String) : UiMessage
}

/** Resolve this message against the current composition's resources. */
@Composable
fun UiMessage.asString(): String = when (this) {
    is UiMessage.Res -> stringResource(id, *args)
    is UiMessage.Plural -> pluralStringResource(id, count, *args)
    is UiMessage.Raw -> text
}

/** [Raw] for a non-null exception message, otherwise the resource [fallback]. */
fun Throwable.toUiMessage(@StringRes fallback: Int): UiMessage = message?.let(UiMessage::Raw) ?: UiMessage.Res(fallback)
