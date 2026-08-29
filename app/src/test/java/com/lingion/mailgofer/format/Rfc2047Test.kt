package com.lingion.mailgofer.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RFC 2047 encoded-word 主题解码。
 * 形如 =?charset?encoding?encoded-text?=  (B=base64 / Q=quoted-printable)
 * 没匹配上 encoded-word 模式就原样返回。
 */
class Rfc2047Test {

    @Test
    fun `B_UTF8_base64_中文主题_解码`() {
        // 线上真邮件的 encoded-word (mbx_fynity3iin 收到的), base64 解出 "推送到手机验证"
        val src = "=?UTF-8?B?5o6o6YCB5Yiw5omL5py66aqM6K+B?="
        assertEquals("推送到手机验证", Rfc2047.decode(src))
    }

    @Test
    fun `Q_UTF8_quoted_printable_含下划线解码`() {
        // "Hello World" QP 编码: =Hello=20=World
        assertEquals("Hello World", Rfc2047.decode("=?UTF-8?Q?Hello=20World?="))
    }

    @Test
    fun `Q_编码中下划线代表空格`() {
        // RFC 2047 在 Q 编码里规定: _ 表示 ASCII 空格(0x20)
        assertEquals("hi there", Rfc2047.decode("=?utf-8?Q?hi_there?="))
    }

    @Test
    fun `Q_编码字面下划线用=5F表示`() {
        // 字面 '_' 在 Q 里编码为 =5F
        assertEquals("hi_there", Rfc2047.decode("=?utf-8?Q?hi=5Fthere?="))
    }

    @Test
    fun `plain_text_无编码_原样返回`() {
        assertEquals("plain subject", Rfc2047.decode("plain subject"))
    }

    @Test
    fun `混合编码_只在encoded_word处解码`() {
        // 主题里夹一个 encoded-word,前后是普通文本; "5rWL6K+V" = "测试"
        val src = "Re: =?UTF-8?B?5rWL6K+V?= test"
        assertEquals("Re: 测试 test", Rfc2047.decode(src))
    }

    @Test
    fun `null_or_blank_原样返回`() {
        assertNull(Rfc2047.decode(null))
        assertEquals("", Rfc2047.decode(""))
    }
}