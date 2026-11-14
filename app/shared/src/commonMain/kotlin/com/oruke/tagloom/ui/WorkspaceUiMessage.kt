package com.oruke.tagloom.ui

import androidx.compose.runtime.Composable
import com.oruke.tagloom.core.transaction.TransactionRecoveryAction
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import com.oruke.tagloom.platform.WorkspaceIntegrityFailure
import com.oruke.tagloom.platform.WorkspaceRecoveryMessage
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.workspace_error_note_save_failed
import tagloom.app.shared.generated.resources.workspace_error_operation_failed
import tagloom.app.shared.generated.resources.workspace_recovery_action_completed
import tagloom.app.shared.generated.resources.workspace_recovery_action_confirmed_and_cleaned
import tagloom.app.shared.generated.resources.workspace_recovery_action_quarantined
import tagloom.app.shared.generated.resources.workspace_recovery_action_rolled_back
import tagloom.app.shared.generated.resources.workspace_recovery_failure_authentication_failed
import tagloom.app.shared.generated.resources.workspace_recovery_failure_hash_mismatch
import tagloom.app.shared.generated.resources.workspace_recovery_failure_missing_file
import tagloom.app.shared.generated.resources.workspace_recovery_isolated_object
import tagloom.app.shared.generated.resources.workspace_recovery_object_blob
import tagloom.app.shared.generated.resources.workspace_recovery_object_change
import tagloom.app.shared.generated.resources.workspace_recovery_object_database
import tagloom.app.shared.generated.resources.workspace_recovery_object_history
import tagloom.app.shared.generated.resources.workspace_recovery_object_note
import tagloom.app.shared.generated.resources.workspace_recovery_object_property
import tagloom.app.shared.generated.resources.workspace_recovery_object_saved_query
import tagloom.app.shared.generated.resources.workspace_recovery_object_tag
import tagloom.app.shared.generated.resources.workspace_recovery_object_view
import tagloom.app.shared.generated.resources.workspace_recovery_object_agent_thread
import tagloom.app.shared.generated.resources.workspace_recovery_object_workspace_config
import tagloom.app.shared.generated.resources.workspace_recovery_transaction_entry
import tagloom.app.shared.generated.resources.workspace_status_backup_created
import tagloom.app.shared.generated.resources.workspace_status_integrity_notice
import tagloom.app.shared.generated.resources.workspace_status_master_key_rotated
import tagloom.app.shared.generated.resources.workspace_status_option_saved_and_selected
import tagloom.app.shared.generated.resources.workspace_status_option_selected
import tagloom.app.shared.generated.resources.workspace_status_pin_changed
import tagloom.app.shared.generated.resources.workspace_status_pin_disabled
import tagloom.app.shared.generated.resources.workspace_status_pin_enabled
import tagloom.app.shared.generated.resources.workspace_status_property_bound_to_tag
import tagloom.app.shared.generated.resources.workspace_status_property_created_and_attached
import tagloom.app.shared.generated.resources.workspace_status_property_saved
import tagloom.app.shared.generated.resources.workspace_status_query_saved
import tagloom.app.shared.generated.resources.workspace_status_tag_created_and_attached
import tagloom.app.shared.generated.resources.workspace_status_tag_updated

/**
 * 表示工作区生命周期与维护流程中可本地化的非敏感反馈。
 *
 * 控制器只保存语义，不保存拼接后的自然语言文本；这样既能随着当前 Locale 渲染，也避免将
 * 底层异常详情、密钥材料或本机路径直接暴露到界面。
 */
internal sealed interface WorkspaceUiMessage {
    /** 当前确认点的密文备份已成功生成。 */
    data object BackupCreated : WorkspaceUiMessage

    /** 当前设备 PIN 已成功更新。 */
    data object PinChanged : WorkspaceUiMessage

    /** 当前设备已开始要求 PIN。 */
    data object PinEnabled : WorkspaceUiMessage

    /** 当前设备已改回系统安全存储自动打开。 */
    data object PinDisabled : WorkspaceUiMessage

    /** 主密钥轮换及新的 Recovery Key 已准备完成。 */
    data object MasterKeyRotated : WorkspaceUiMessage

    /** 当前搜索条件已保存为工作区对象。 */
    data object QuerySaved : WorkspaceUiMessage

    /** 新对象类型已创建并应用到当前笔记。 */
    data object TagCreatedAndAttached : WorkspaceUiMessage

    /** 对象类型定义已更新但未自动应用到当前笔记。 */
    data object TagUpdated : WorkspaceUiMessage

    /** 新属性已创建并加入当前笔记。 */
    data object PropertyCreatedAndAttached : WorkspaceUiMessage

    /** 新属性定义已保存到工作区目录。 */
    data object PropertySaved : WorkspaceUiMessage

    /** 属性已创建并绑定到指定对象类型。 */
    data class PropertyBoundToTag(
        /** 已确认对象类型的用户可见名称。 */
        val tagName: String,
    ) : WorkspaceUiMessage

    /** 自由选项已先于笔记值写入并被选中。 */
    data object OptionSavedAndSelected : WorkspaceUiMessage

    /** 已选择一个既有自由选项。 */
    data object ExistingOptionSelected : WorkspaceUiMessage

    /** 打开会话时隔离了无法认证的对象。 */
    data object IntegrityNotice : WorkspaceUiMessage

    /** 当前 Note 保存失败但编辑器草稿仍可继续修改。 */
    data object NoteSaveFailed : WorkspaceUiMessage

    /** 平台或密码学操作失败时使用的脱敏错误。 */
    data object OperationFailed : WorkspaceUiMessage
}

/**
 * 按当前 Compose Locale 将工作区反馈语义渲染为用户可见文本。
 *
 * @return 当前语言对应的资源文本。
 */
@Composable
internal fun WorkspaceUiMessage.localizedText(): String = when (this) {
    WorkspaceUiMessage.BackupCreated -> stringResource(Res.string.workspace_status_backup_created)
    WorkspaceUiMessage.PinChanged -> stringResource(Res.string.workspace_status_pin_changed)
    WorkspaceUiMessage.PinEnabled -> stringResource(Res.string.workspace_status_pin_enabled)
    WorkspaceUiMessage.PinDisabled -> stringResource(Res.string.workspace_status_pin_disabled)
    WorkspaceUiMessage.MasterKeyRotated -> stringResource(Res.string.workspace_status_master_key_rotated)
    WorkspaceUiMessage.QuerySaved -> stringResource(Res.string.workspace_status_query_saved)
    WorkspaceUiMessage.TagCreatedAndAttached -> stringResource(Res.string.workspace_status_tag_created_and_attached)
    WorkspaceUiMessage.TagUpdated -> stringResource(Res.string.workspace_status_tag_updated)
    WorkspaceUiMessage.PropertyCreatedAndAttached -> stringResource(
        Res.string.workspace_status_property_created_and_attached,
    )
    WorkspaceUiMessage.PropertySaved -> stringResource(Res.string.workspace_status_property_saved)
    is WorkspaceUiMessage.PropertyBoundToTag -> stringResource(
        Res.string.workspace_status_property_bound_to_tag,
        tagName,
    )
    WorkspaceUiMessage.OptionSavedAndSelected -> stringResource(
        Res.string.workspace_status_option_saved_and_selected,
    )
    WorkspaceUiMessage.ExistingOptionSelected -> stringResource(
        Res.string.workspace_status_option_selected,
    )
    WorkspaceUiMessage.IntegrityNotice -> stringResource(Res.string.workspace_status_integrity_notice)
    WorkspaceUiMessage.NoteSaveFailed -> stringResource(Res.string.workspace_error_note_save_failed)
    WorkspaceUiMessage.OperationFailed -> stringResource(Res.string.workspace_error_operation_failed)
}

/**
 * 按当前 Compose Locale 将恢复状态语义渲染为用户可见文本。
 *
 * @return 当前语言对应的脱敏恢复说明。
 */
@Composable
internal fun WorkspaceRecoveryMessage.localizedText(): String = when (this) {
    is WorkspaceRecoveryMessage.RecoveredTransaction -> stringResource(
        Res.string.workspace_recovery_transaction_entry,
        transactionIdPrefix,
        action.localizedText(),
    )

    is WorkspaceRecoveryMessage.IsolatedObject -> stringResource(
        Res.string.workspace_recovery_isolated_object,
        kind.localizedText(),
        objectIdPrefix,
        failure.localizedText(),
    )
}

/**
 * 返回事务恢复动作的当前语言文本。
 *
 * @return 已本地化的恢复动作说明。
 */
@Composable
private fun TransactionRecoveryAction.localizedText(): String = when (this) {
    TransactionRecoveryAction.CONFIRMED_AND_CLEANED -> stringResource(
        Res.string.workspace_recovery_action_confirmed_and_cleaned,
    )

    TransactionRecoveryAction.COMPLETED -> stringResource(Res.string.workspace_recovery_action_completed)
    TransactionRecoveryAction.ROLLED_BACK -> stringResource(Res.string.workspace_recovery_action_rolled_back)
    TransactionRecoveryAction.QUARANTINED -> stringResource(Res.string.workspace_recovery_action_quarantined)
}

/**
 * 返回 manifest 对象类别的当前语言文本。
 *
 * @return 已本地化的对象类别说明。
 */
@Composable
private fun ManifestObjectKind.localizedText(): String = when (this) {
    ManifestObjectKind.NOTE -> stringResource(Res.string.workspace_recovery_object_note)
    ManifestObjectKind.TAG -> stringResource(Res.string.workspace_recovery_object_tag)
    ManifestObjectKind.PROPERTY -> stringResource(Res.string.workspace_recovery_object_property)
    ManifestObjectKind.BLOB -> stringResource(Res.string.workspace_recovery_object_blob)
    ManifestObjectKind.CHANGE -> stringResource(Res.string.workspace_recovery_object_change)
    ManifestObjectKind.SAVED_QUERY -> stringResource(Res.string.workspace_recovery_object_saved_query)
    ManifestObjectKind.DATABASE -> stringResource(Res.string.workspace_recovery_object_database)
    ManifestObjectKind.VIEW -> stringResource(Res.string.workspace_recovery_object_view)
    ManifestObjectKind.WORKSPACE_CONFIG -> stringResource(Res.string.workspace_recovery_object_workspace_config)
    ManifestObjectKind.AGENT_THREAD -> stringResource(Res.string.workspace_recovery_object_agent_thread)
    ManifestObjectKind.HISTORY -> stringResource(Res.string.workspace_recovery_object_history)
}

/**
 * 返回对象隔离原因的当前语言文本。
 *
 * @return 已本地化的完整性失败说明。
 */
@Composable
private fun WorkspaceIntegrityFailure.localizedText(): String = when (this) {
    WorkspaceIntegrityFailure.MISSING_FILE -> stringResource(Res.string.workspace_recovery_failure_missing_file)
    WorkspaceIntegrityFailure.HASH_MISMATCH -> stringResource(Res.string.workspace_recovery_failure_hash_mismatch)
    WorkspaceIntegrityFailure.AUTHENTICATION_FAILED -> stringResource(
        Res.string.workspace_recovery_failure_authentication_failed,
    )
}
