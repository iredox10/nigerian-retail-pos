package pos.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** Groups the displayed naira amount while preserving the exact entered digits and cursor. */
object MoneyInputTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val wholeEnd = raw.indexOf('.').let { if (it < 0) raw.length else it }
        if (raw.isEmpty() || raw.take(wholeEnd).any { it !in '0'..'9' } || raw.count { it == '.' } > 1 ||
            raw.drop(wholeEnd + 1).any { it !in '0'..'9' }) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val positions = IntArray(raw.length + 1)
        val grouped = buildString {
            raw.forEachIndexed { index, character ->
                if (index > 0 && index < wholeEnd && (wholeEnd - index) % 3 == 0) append(',')
                positions[index] = length
                append(character)
            }
            positions[raw.length] = length
        }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = positions[offset.coerceIn(0, raw.length)]
            override fun transformedToOriginal(offset: Int): Int = grouped.take(offset.coerceIn(0, grouped.length)).count { it != ',' }
        }
        return TransformedText(AnnotatedString(grouped), mapping)
    }
}
