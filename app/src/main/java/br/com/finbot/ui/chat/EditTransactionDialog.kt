package br.com.finbot.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import br.com.finbot.assistant.ActionPayload
import br.com.finbot.domain.Money
import br.com.finbot.domain.TransactionRules
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

private val INPUT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT)

/** Corrige valor, descrição, categoria e data antes de confirmar. Confirmar aqui grava (uso único do cartão). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditTransactionDialog(
    state: EditState,
    onDismiss: () -> Unit,
    onConfirm: (ActionPayload.CreateTransaction) -> Unit,
) {
    val original = state.payload
    var amount by rememberSaveable { mutableStateOf(Money.format(original.amountCents).removePrefix("R$ ")) }
    var description by rememberSaveable { mutableStateOf(original.description) }
    var categoryId by rememberSaveable { mutableStateOf(original.categoryId) }
    var dateText by rememberSaveable { mutableStateOf(original.date.format(INPUT_DATE)) }
    var errors by rememberSaveable { mutableStateOf(listOf<String>()) }

    fun validate(): ActionPayload.CreateTransaction? {
        val found = mutableListOf<String>()
        val cents = Money.parseCents(amount)
        if (cents == null || !Money.isValidAmount(cents)) found += "Valor inválido."
        val desc = description.trim()
        if (desc.isEmpty() || desc.length > TransactionRules.MAX_DESCRIPTION) found += "Descrição deve ter de 1 a ${TransactionRules.MAX_DESCRIPTION} caracteres."
        val date = try {
            LocalDate.parse(dateText.trim(), INPUT_DATE)
        } catch (_: DateTimeParseException) {
            null
        }
        if (date == null) found += "Data inválida (use dd/mm/aaaa)."
        if (date != null && date.isAfter(state.today)) found += "A data não pode estar no futuro."
        errors = found
        if (found.isNotEmpty()) return null
        return original.copy(amountCents = cents!!, description = desc, categoryId = categoryId, date = date!!)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar lançamento") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Valor (R$)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(TransactionRules.MAX_DESCRIPTION) },
                    label = { Text("Descrição") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it },
                    label = { Text("Data (dd/mm/aaaa)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Categoria", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.categories.forEach { category ->
                        FilterChip(
                            selected = category.id == categoryId,
                            onClick = { categoryId = if (categoryId == category.id) null else category.id },
                            label = { Text(category.name) },
                        )
                    }
                }
                errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { validate()?.let(onConfirm) }) { Text("Confirmar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Voltar") } },
    )
}
