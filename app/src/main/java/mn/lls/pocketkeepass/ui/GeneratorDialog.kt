package mn.lls.pocketkeepass.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import mn.lls.pocketkeepass.R
import mn.lls.pocketkeepass.core.PasswordOptions
import mn.lls.pocketkeepass.core.Symbols
import mn.lls.pocketkeepass.core.passwordLookalikes
import mn.lls.pocketkeepass.core.passwordSymbols
import mn.lls.pocketkeepass.edit.GENERATOR_LENGTHS
import mn.lls.pocketkeepass.edit.GeneratorState
import mn.lls.pocketkeepass.security.AccessibleToToolsOnly

/**
 * The password generator: the password it made, how strong that kind of password is, and the
 * options. Every change to the options makes a new password at once. "Use" puts it in the field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneratorDialog(
    state: GeneratorState,
    onChangeOptions: (PasswordOptions) -> Unit,
    onGenerateAgain: () -> Unit,
    onUse: () -> Unit,
    onDismiss: () -> Unit,
) {
    val options = state.options
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.generate_password)) },
        text = {
            AccessibleToToolsOnly() // a dialog is a window of its own
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GeneratedPassword(state, onGenerateAgain)

                Column {
                    Text(stringResource(R.string.password_length, options.length.toInt()))
                    Slider(
                        value = options.length.toFloat(),
                        onValueChange = { value ->
                            val length = value.toInt().toUInt()
                            if (length != options.length) onChangeOptions(options.copy(length = length))
                        },
                        valueRange = GENERATOR_LENGTHS.first.toFloat()..GENERATOR_LENGTHS.last.toFloat(),
                        steps = GENERATOR_LENGTHS.last - GENERATOR_LENGTHS.first - 1,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KindChip(R.string.chars_lowercase, R.string.chars_lowercase_description, options.lowercase) {
                        onChangeOptions(options.copy(lowercase = it))
                    }
                    KindChip(R.string.chars_uppercase, R.string.chars_uppercase_description, options.uppercase) {
                        onChangeOptions(options.copy(uppercase = it))
                    }
                    KindChip(R.string.chars_digits, R.string.chars_digits_description, options.digits) {
                        onChangeOptions(options.copy(digits = it))
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.symbols))
                    val choices = listOf(
                        Symbols.COMMON to R.string.symbols_common,
                        Symbols.ALL to R.string.symbols_all,
                        Symbols.NONE to R.string.symbols_none,
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        choices.forEachIndexed { i, (symbols, label) ->
                            SegmentedButton(
                                selected = options.symbols == symbols,
                                onClick = { onChangeOptions(options.copy(symbols = symbols)) },
                                shape = SegmentedButtonDefaults.itemShape(i, choices.size),
                            ) { Text(stringResource(label)) }
                        }
                    }
                    val list = remember(options.symbols) { passwordSymbols(options.symbols) }
                    Text(
                        when (options.symbols) {
                            Symbols.COMMON -> stringResource(R.string.symbols_common_explained, list)
                            Symbols.ALL -> stringResource(R.string.symbols_all_explained, list)
                            Symbols.NONE -> stringResource(R.string.symbols_none_explained)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val lookalikes = remember { passwordLookalikes().toList().joinToString(" ") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.avoid_lookalikes, lookalikes), Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = options.avoidLookalikes,
                        onCheckedChange = { onChangeOptions(options.copy(avoidLookalikes = it)) },
                    )
                }

                Text(
                    stringResource(R.string.every_kind_explained),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onUse, enabled = state.password != null) { Text(stringResource(R.string.use_password)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The password in monospace (so l, I, 1, O, and 0 can be told apart), its strength, and "again". */
@Composable
private fun GeneratedPassword(state: GeneratorState, onGenerateAgain: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            ) {
                if (state.password != null) {
                    Text(
                        state.password,
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    )
                    state.strengthBits?.let { bits ->
                        Text(
                            stringResource(R.string.password_strength, stringResource(strengthLabel(bits)), floor(bits).toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
            }
            IconButton(onClick = onGenerateAgain, enabled = state.password != null) {
                Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.generate_again))
            }
        }
    }
}

@Composable
private fun KindChip(label: Int, description: Int, selected: Boolean, onChange: (Boolean) -> Unit) {
    val spoken = stringResource(description)
    FilterChip(
        selected = selected,
        onClick = { onChange(!selected) },
        label = { Text(stringResource(label)) },
        // "a–z" read aloud is unclear; say what it is.
        modifier = Modifier.semantics { contentDescription = spoken },
    )
}

/**
 * How a password of this many random bits rates. Finding it takes 2^(bits - 1) guesses on
 * average: below 45 bits that's within reach of an attacker with a stolen password hash, and
 * from 75 bits up it's out of reach for anyone.
 */
private fun strengthLabel(bits: Double): Int = when {
    bits < 45 -> R.string.strength_weak
    bits < 75 -> R.string.strength_fair
    bits < 100 -> R.string.strength_strong
    else -> R.string.strength_very_strong
}
