package dev.promethe.channels

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class ChannelsTest {
    private fun createMockClient(): HttpClient =
        HttpClient(
            MockEngine { _ ->
                respond(
                    content = """{"ok":true,"success":true,"d":{"id":"msg-id"},
                        |"access_token":"token","tenant_access_token":"token","expires_in":7200,
                        |"sid":"msg-sid","channel_id":"id","text":"turn on light","errcode":0,"code":0}
                    """.trimMargin(),
                    status = HttpStatusCode.OK,
                )
            },
        )

    @Test
    fun testTelegramChannel() =
        runTest {
            val channel = TelegramChannel("token", createMockClient())
            assertTrue(channel.sendMessage(123456L, "Hello Telegram"))
            assertNotNull(
                channel.parseUpdate(
                    """{"update_id":1,
                |"message":{"message_id":12,"chat":{"id":123},"text":"hi"}}
                    """.trimMargin(),
                ),
            )
        }

    @Test
    fun testDiscordChannel() =
        runTest {
            val channel = DiscordChannel("token", createMockClient())
            assertTrue(channel.sendMessage("channel-1", "Hello Discord"))
            assertNotNull(channel.parseInteraction("""{"type":1}"""))
        }

    @Test
    fun testSlackChannel() =
        runTest {
            val channel = SlackChannel("token", createMockClient())
            assertTrue(channel.sendMessage("#general", "Hello Slack"))
            val (type, _) = channel.parseEvent("""{"type":"url_verification","challenge":"my-challenge"}""")
            assertEquals("challenge", type)
        }

    @Test
    fun testWhatsAppChannel() =
        runTest {
            val channel = WhatsAppChannel("12345", "token", createMockClient())
            assertTrue(channel.sendMessage("14155552671", "Hello WhatsApp"))
            val updates = channel.parseUpdate(
                """{"entry":[{"changes":[{"field":"messages","value":{"messages":[{"from":"14155552671","id":"msg-1","text":{"body":"Hi"}}]}}]}]}""",
            )
            assertEquals(1, updates.size)
            assertEquals("Hi", updates[0].second)
        }

    @Test
    fun testSignalChannel() =
        runTest {
            val channel = SignalChannel("http://localhost:8080", "+123456789", createMockClient())
            assertTrue(channel.sendMessage("+123456789", "Hello Signal"))
            val update = channel.parseUpdate("""{"envelope":{"source":"+123456789","dataMessage":{"message":"Hi","timestamp":1000}}}""")
            assertNotNull(update)
            assertEquals("Hi", update.text)
        }

    @Test
    fun testEmailChannel() =
        runTest {
            val channel = EmailChannel(
                smtpHost = "api.sendgrid.com",
                smtpPort = 587,
                username = "apikey",
                password = "password",
                fromAddress = "me@example.com",
                httpClient = createMockClient(),
                apiMode = EmailChannel.ApiMode.SENDGRID,
            )
            assertTrue(channel.sendMessage("you@example.com", "Hello Email", "Subject line"))
        }

    @Test
    fun testSmsChannel() =
        runTest {
            val channel = SmsChannel("sid", "token", "+11111", createMockClient())
            assertNotNull(channel.sendMessage("+22222", "Hello SMS"))
            val inbound = channel.parseUpdate("From=%2B22222&Body=Hi&MessageSid=SM123")
            assertNotNull(inbound)
            assertEquals("+22222", inbound.from)
        }

    @Test
    fun testMatrixChannel() =
        runTest {
            val channel = MatrixChannel("https://matrix.org", "token", createMockClient())
            assertTrue(channel.sendMessage("!room:matrix.org", "Hello Matrix"))
            val parsed = channel.parseEvent(
                """{"type":"m.room.message","content":{"body":"Hi","msgtype":"m.text"},"sender":"@user:matrix.org","event_id":"$1"}""",
            )
            assertNotNull(parsed)
            assertEquals("Hi", parsed.content?.body)
        }

    @Test
    fun testTeamsChannel() =
        runTest {
            val channel = TeamsChannel("app-1", "secret", createMockClient())
            assertTrue(channel.sendMessage("https://teams-webhook.com", "conv-1", "act-1", "Hello Teams", "token-1"))
            val activity = channel.parseActivity(
                """{"type":"message","id":"act-1","text":"Hi","from":{"id":"user-1"},"conversation":{"id":"conv-1"},"serviceUrl":"https://teams-webhook.com"}""",
            )
            assertNotNull(activity)
            assertEquals("Hi", activity.text)
        }

    @Test
    fun testMattermostChannel() =
        runTest {
            val channel = MattermostChannel("https://mattermost-webhook.com", "token-1", createMockClient())
            assertTrue(channel.sendMessage("channel-1", "Hello Mattermost"))
        }

    @Test
    fun testDingTalkChannel() =
        runTest {
            val channel = DingTalkChannel("https://dingtalk-webhook.com", httpClient = createMockClient())
            assertTrue(channel.sendMessage("Hello DingTalk"))
        }

    @Test
    fun testFeishuChannel() =
        runTest {
            val channel = FeishuChannel("appId", "secret", createMockClient())
            assertTrue(channel.sendMessage("chat-1", "Hello Feishu"))
        }

    @Test
    fun testWeComChannel() =
        runTest {
            val channel = WeComChannel(webhookKey = "key-1", httpClient = createMockClient())
            assertTrue(channel.sendMessage("Hello WeCom"))
        }

    @Test
    fun testLineChannel() =
        runTest {
            val channel = LineChannel("token", createMockClient())
            assertTrue(channel.pushMessage("user-1", "Hello Line"))
            val incoming = channel.parseUpdate(
                """{"events":[{"type":"message","replyToken":"token-1","source":{"userId":"user-1"},"message":{"type":"text","text":"Hi","id":"msg-1"}}]}""",
            )
            assertEquals(1, incoming.size)
            assertEquals("Hi", incoming[0].text)
        }

    @Test
    fun testQqChannel() =
        runTest {
            val channel = QqChannel("app-1", "token", createMockClient())
            assertTrue(channel.sendMessage("channel-1", "Hello QQ"))
            val incoming = channel.parseUpdate("""{"d":{"channel_id":"channel-1","id":"msg-1","content":"Hi","author":{"id":"user-1"}}}""")
            assertNotNull(incoming)
            assertEquals("Hi", incoming.content)
        }

    @Test
    fun testWeixinChannel() =
        runTest {
            val channel = WeixinChannel("appId", "secret", createMockClient())
            assertTrue(channel.sendCustomMessage("user-1", "Hello WeChat"))
            val incoming = channel.parseUpdate(
                "<xml><FromUserName><![CDATA[user-1]]></FromUserName><Content><![CDATA[Hi]]></Content><MsgType><![CDATA[text]]></MsgType></xml>",
            )
            assertNotNull(incoming)
            assertEquals("Hi", incoming.content)
        }

    @Test
    fun testBlueBubblesChannel() =
        runTest {
            val channel = BlueBubblesChannel("http://localhost:1234", "password", createMockClient())
            assertTrue(channel.sendMessage("chat-guid-1", "Hello iMessage"))
            val incoming = channel.parseUpdate(
                """{"guid":"guid-1","text":"Hi","chats":[{"guid":"chat-guid-1"}],"handle":{"address":"sender"}}""",
            )
            assertNotNull(incoming)
            assertEquals("Hi", incoming.text)
        }

    @Test
    fun testNtfyChannel() =
        runTest {
            val channel = NtfyChannel("https://ntfy.sh", "topic-1", createMockClient())
            assertTrue(channel.sendMessage("Hello ntfy"))
            val incoming = channel.parseUpdate("""{"event":"message","id":"1","topic":"topic-1","message":"Hi"}""")
            assertNotNull(incoming)
            assertEquals("Hi", incoming.message)
        }

    @Test
    fun testHomeAssistantChannel() =
        runTest {
            val channel = HomeAssistantChannel("http://localhost:8123", "token", createMockClient())
            val response = channel.sendMessage("Hello Home Assistant")
            assertTrue(response.contains("ok"))
            val incoming = channel.parseUpdate("""{"text":"turn on light","language":"en"}""")
            assertNotNull(incoming)
            assertEquals("turn on light", incoming.text)
        }
}
