English · [Русский](assistant.ru.md)

# On-screen assistant

Starting with Axiko 0.3.0, the Android assistant shortcut can open a transparent overlay over the
current app. Translucent bubbles show recent messages while the agent listens and responds.
The app below stays usable around the visible assistant controls. You can respond to questions
or approve a plan in the overlay. The regular Axiko chat keeps its selected conversation.

## Set up and use

1. In Axiko, open **Settings → Assistant → Choose an Android assistant** and select Axiko in
   Android's settings. Use **Try the panel** to check the setup. The system gesture or
   hardware shortcut depends on your phone and navigation settings.
2. To speak, grant Axiko microphone permission and download an on-device speech recognition
   model in **Settings → Voice**. The panel also accepts typed messages.
3. To open the panel by name, enable **Respond to the name** under **Voice** and **Open the
   panel on wake word** under **Assistant**. Saying the name on its own opens listening. A phrase supplied
   with the name goes straight to the panel's conversation. Android's system assistant role is
   required for this panel. The existing voice-only behavior is available without that role.
   Under **Voice → Agent wake phrases**, you can assign one or more wake phrases to each
   agent. Say one of those phrases to open that agent's own conversation.

Each invocation starts a fresh conversation. Use **Sessions** to open a recent assistant
conversation or start another. The keyboard button opens the same conversation in text mode.
The center microphone then dictates into an editable draft. Send it when it is ready. Drafts
and temporary conversations stay in memory while the Axiko process lives. A saved conversation
can also be opened in the regular chat after its first message. **Open Axiko** returns to the
main app from any assistant conversation. Closing the overlay releases the conversation's
microphone and silences its voice.
The task can continue in the background. Stopping speech, stopping the microphone and
cancelling the agent's task are separate controls. Switching to text stops voice input and
lets the current reply finish playing.

Press and hold the center microphone in voice mode to start a continuous conversation. It
keeps listening across turns until you tap the microphone again, close the panel, or reach
the configured idle timeout. The default is 180 seconds. The timer waits while the agent is
working. Set it to 0 to keep listening until you stop or close the panel.

The overlay uses the audio output selected by Android. You can speak over a reply with
headphones or through the phone's speaker when Axiko has access to the playback samples.
Axiko compares audio from the microphone with those samples to avoid picking up the reply.
It keeps filtering the speaker's echo briefly after a reply ends or is interrupted, while
keeping the start of a confirmed interruption. Axiko decodes WAV and other supported formats
for this comparison. If playback supplies no samples, tap **Stop narration** and keep speaking.

Ask the agent to open a recent chat by topic, for example, “Open my chat about creatine.”
Axiko searches saved chats by title and recent messages, then opens the match in the same
panel or chat view. You can also find a saved chat from **Sessions**. Say “Invite Nora” to add
an agent to the current chat, then address her directly with “Nora, check this.” Typing
`@Nora check this` can invite and address her in one turn. The reply appears in the same chat
under her name. Saved chats keep their participants when you reopen them.

Axiko automatically retries temporary model failures such as network timeouts. The chat
retry setting accepts 0–10 automatic retries, with 2 by default. Each retry resumes from the
failed model step and reuses the results of tools that already ran. If the reply still fails,
use **Retry request** to repeat the failed step from the chat or the assistant panel.

**Add screen** attaches a one-time snapshot of the app shown when the panel opened to the next
message. Axiko can use only the supplied screen data, which may include text and a screenshot.
The panel still works if the capture is unavailable. Adding a screen to a message can share the
captured content with your selected model provider. The **Allow adding the screen** preference
controls whether the panel accepts this context. Android's screen context settings must also allow it.

Use **File or picture** to pick a document or an existing photo. **Take photo** opens the camera
after Android grants access. Returning from either picker keeps your draft and the current
conversation. Add up to four attachments, 25 MB each. The panel stops listening when the picker
opens. Press the microphone to resume voice input after returning. You can remove attachments
before asking your question. Sending the message adds the chosen files to the conversation like
regular chat attachments. Images are sent as images when the selected model supports vision.

The name detector listens locally when enabled and uses battery while watching for the name.
Background microphone access, activation with the display off, and hardware shortcuts depend on
Android and the manufacturer's settings. The system and the foreground app control what screen
context is available. Treat temporary history as a chat storage setting. Actions the agent takes
and any data sent to its providers remain subject to their regular behavior.

## Customize the panel

The **Assistant** tab exposes these preferences.

| Setting | Values and behavior |
|---|---|
| `agent` | Agent to use for new invocations. Defaults to the main agent. |
| `activation` | `manual` waits for the mic button, `voice` starts listening when the panel opens. |
| `conversationIdleSeconds` | Continuous conversation idle timeout, 0–3,600 seconds. Defaults to 180. Set 0 for no idle timeout. |
| `wakePanel` | Opens the panel on name detection when the separate wake setting is on. |
| `voice` | `default` follows Axiko's configured voice, `local` uses the phone's voice, `silent` keeps replies in text. |
| `history` | `save` puts the conversation in chat history, `temporary` keeps it in memory. |
| `screen` | Allows the session to accept screen context for an explicit attachment. |
| `layout` | `bottom` anchors at the bottom, `side` uses a side layout on a wide display. |
| `opacity` | Opacity of the visible assistant surfaces, from 0 to 100. |
| `animation` | Enables the voice wave animation. The UI honors reduced-motion preferences. |
| `mascot` | Shows the editable Axiko mascot in the panel. |

The panel uses `ui/assistant/index.html`, `assistant.css` and `assistant.js` in the editable
workspace. Change these files to adjust the layout, theme, typography and animation without
rebuilding the APK. You can edit them in Studio or ask your agent to update them. They use the
same local UI kit, theme tokens and translations as other Axiko pages. Reopen the panel to apply
edits. The APK contains the factory copy, and workspace updates
normally preserve local edits. Android Back dismisses the panel. If the editable and factory
pages fail to load, the shell provides basic text input and native close and microphone controls.
[Factory panel](../app/src/main/assets/factory/ui/assistant/index.html) · [Workspace layers](architecture.md) · [Studio](web-hub.md#studio-and-file-revisions)

The system window, input routing, microphone and conversation ownership belong to the shell.
The session-scoped bridge handles messages, supported session changes, attachments and questions.
`assistant.pickAttachment(kind)` opens the Android picker with `file`, `photo` or `camera`.
`assistant.removeAttachment(id)` removes a pending item. `assistant.status` reports availability
as `attachmentsAvailable`, picker activity as `attachmentsPicking`, and `attachments` as an array
of `id`, `name` and `kind` (`image` or `file`). The panel bridge also exposes continuous voice, model
retry and chat participants.
The factory page reports the interactive areas in CSS viewport pixels through
`assistant.hitAreas`. Update those areas when your layout changes so Android can route touches
to the underlying app elsewhere. In browser fixtures, stub the bridge and exercise the actual
local assets. Validate the system gesture, touch routing, microphone release and screen capture
on Android. Check both displays and fold transitions on a foldable device.
