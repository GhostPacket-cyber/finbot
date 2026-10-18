package br.com.finbot.data

// Enums são gravados pelo nome (TEXT). Renomear uma constante exige migração.

enum class TransactionKind { INCOME, EXPENSE }

enum class AccountType { CHECKING, SAVINGS, CASH, CREDIT_CARD, OTHER }

enum class TransactionSource { MANUAL, CHAT, AI, IMPORT_OFX, IMPORT_CSV, BANK_NOTIFICATION, BILL }

/** Somente CONFIRMED entra em saldos e relatórios. */
enum class TransactionStatus { CONFIRMED, PENDING_REVIEW, DISCARDED }

enum class BillOccurrenceStatus { PENDING, PAID, SKIPPED }

/** Regra para vencimentos nos dias 29–31 em meses mais curtos. */
enum class ShortMonthPolicy { LAST_DAY_OF_MONTH, FIRST_DAY_OF_NEXT_MONTH }

enum class NotificationStatus { SENT, FAILED }

enum class ActionOrigin { CHAT, AI, IMPORT, BANK_NOTIFICATION }

enum class PendingActionStatus { PENDING, CONFIRMED, CANCELLED }

enum class ImportSource { OFX, CSV }

enum class AuditAction { CREATE, UPDATE, DELETE, CONFIRM, DISCARD, PAY, UNPAY, RESTORE, MERGE }

enum class ChatRole { USER, ASSISTANT, SYSTEM }
