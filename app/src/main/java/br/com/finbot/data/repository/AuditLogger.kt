package br.com.finbot.data.repository

import br.com.finbot.data.AuditAction
import br.com.finbot.data.dao.AuditLogDao
import br.com.finbot.data.entity.AuditLogEntity
import org.json.JSONObject
import java.time.Instant

/** Deve ser chamado dentro da mesma transação da alteração auditada. */
class AuditLogger(private val dao: AuditLogDao) {

    suspend fun record(entity: String, entityUuid: String, action: AuditAction, at: Instant, summary: Map<String, Any?>) {
        val json = JSONObject()
        summary.forEach { (key, value) -> json.put(key, value?.toString() ?: JSONObject.NULL) }
        dao.insert(
            AuditLogEntity(
                entity = entity,
                entityUuid = entityUuid,
                action = action,
                changedAt = at,
                summaryJson = json.toString(),
            ),
        )
    }

    companion object {
        const val TRANSACTION = "transaction"
        const val CATEGORY = "category"
        const val BILL = "bill"
        const val BILL_OCCURRENCE = "bill_occurrence"
    }
}
