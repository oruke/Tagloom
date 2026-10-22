package com.oruke.tagloom.core.model

import kotlinx.serialization.Serializable

/** 记录自动网页字段最后一次确认的值；相等才允许回填，用户编辑后显式关闭自动来源。 */
@Serializable
data class LinkMetadataProvenance(
    /** 元数据对应的规范化 URL。 */
    val url: String,
    /** 标题是否仍接受自动更新。 */
    val automaticTitle: Boolean = true,
    /** 描述是否仍接受自动更新。 */
    val automaticDescription: Boolean = true,
    /** 上次自动写入的标题，用于并发与外部入口的覆盖保护。 */
    val title: String,
    /** 上次自动写入的描述，缺失时为空文本。 */
    val description: String = "",
)

/** 客户端网页获取端口返回的精简元数据，不含页面正文或浏览器凭据。 */
data class WebPageMetadata(
    /** 已压缩空白的标题，缺失时为空。 */
    val title: String?,
    /** 已压缩空白的描述，缺失时为空。 */
    val description: String?,
)
