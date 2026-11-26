<p align="center">
  <a href="assets/promo/axiko-promo.mp4"><img src="assets/promo/axiko-promo.webp" width="820" alt="Axiko in 40 seconds: a plain home screen, a request in the chat, a new screen, and five owners whose apps look nothing alike"></a>
</p>

<p align="center">English · <a href="README.ru.md">Русский</a></p>

# Axiko

**Software you finish yourself.**

Axiko is a personal Android app with a language model at its core. You say what you need, in text
or by voice, and an agent inside the app writes it: the logic, the screen, the card on Home. It
happens on the phone, without a rebuild, an update or an app store.

## The idea

An app is made once, for millions of people, and ships finished. Everyone gets the same screens.
The settings page lists the choices someone else thought to offer. If the app lacks what you need,
you wait for a release or look for another app.

Axiko ships unfinished on purpose. On day one it is a home screen, a chat and an agent that can
write the rest. A reader ends up with a reading log and a quote of the day. A runner ends up with
intervals and weekly distance. What the app becomes depends on who holds it, and no two copies
stay alike for long.

## Principles

- **It is yours.** Everything the app is made of lives as files in a working folder on your phone:
  screens, modules, themes, the menu, the mascot, the agent's own prompt and tools. You can read
  them, edit them, back them up and carry them to another phone.
- **Everything can change.** The agent edits the same files the app runs from, so there is no fixed
  list of options. The chat, Home and Settings are as editable as any module.
- **No gatekeeper.** A new feature does not wait for a release. Modules come from the agent, from
  you, or from any repository you choose to connect.
- **Every change can be undone.** The shell takes a snapshot before each turn of the agent. History
  shows what changed, file by file, and rolls it back. Safe mode starts the factory interface if
  the edited one breaks. An encrypted backup holds everything personal in one file.
- **Your model, your data.** Any OpenAI-compatible provider works, and so do models that run on the
  phone. Provider keys and shell-owned secrets are managed by the shell instead of being returned
  through the normal script/model APIs. What the agent reads goes to the model you chose, so pick an on-device model for
  what must not leave the phone.

## How it works

1. **You ask**, in the chat or by voice: "Make a habit tracker with daily marks."
2. **The agent plans and writes.** It is a loop around the model you chose, with tools for files,
   code and the phone. It asks when it is unsure, then writes a module: `module.json`, the logic,
   a screen, a card for Home.
3. **It checks its own work.** It opens the new screen for itself, reads the console, looks at a
   screenshot and fixes what is broken.
4. **The screen is on Home.** If you do not like it, say so, or roll the turn back.

Under that there are three layers:

| Layer | Where | Who changes it |
|---|---|---|
| Shell: WebView, JavaScript engine, agent loop, voice, system services | the APK (Kotlin) | developers, rarely |
| Factory copy of the working folder: screens, library, the agent's prompt | inside the APK | developers |
| Working folder on the device: the same, plus your modules, agents and themes | on the phone | the agent and you, all the time |

Screens are plain HTML shown in a WebView straight from the working folder, with no build step.
Logic runs in Rhino, a JavaScript engine that can call Android classes directly, so a module can
reach anything the phone offers. The agent loop and the safety net (snapshots, safe mode, secrets)
live in the shell, where the agent cannot edit them.

More detail: [Architecture](docs/architecture.md) ([Русский](docs/architecture.ru.md)).

## What it can do today

- **Build things.** Screens, modules, games, routines that run on a schedule or on an event, other
  agents with their own chats and models, and new tools for itself.
- **Sense the phone.** Read and operate the screen of other apps, read notifications and reply in
  them, act as the system assistant, float a button over other apps. You turn each of these on
  yourself in system settings.
- **Listen and speak.** Speech recognition on the device (GigaAM v3, a Russian-language model),
  speech through a provider or a voice on the phone, and a name you can call it by.
- **Assist on screen.** Open an editable voice and text panel above the current app, work with
  a separate conversation and keep the agent's task going when the panel closes. [Setup](docs/assistant.md)
- **Work like a colleague.** It asks clarifying questions as a form, proposes a plan and marks its
  steps, runs parts of a job in parallel, and keeps changes as a draft until you decide.
- **Reach outside.** Web search and page reading, MCP servers, sign-in to services over OAuth,
  long-term memory with search by words and by meaning.
- **Stay offline.** On-device models (Gemma, Qwen and others through LiteRT-LM) and a Linux
  environment for Python, Node and ffmpeg.
- **Open on a computer.** A web hub served by the phone on your local network: your modules, the
  chat and a studio with a file editor, a preview and a log. The phone is also an MCP server for
  agents on other machines.
- **Install what others made.** A store of modules and games from the repositories you connect,
  with updates, rollback and mods that change another package.
- **Speak your language.** The interface ships in Russian and English. The agent can add another.

## Make it yours

<p align="center">
  <img src="assets/promo/owners.png" width="820" alt="Five phones with the same build of Axiko: a cream serif reading app, a black and lime running app, a green terminal, a pink app for a kid and a bare white one with large numbers">
</p>

These five phones run the same build. The difference is a theme file, a menu file, a mascot file
and a few modules. Ask the agent for any of it, or change it yourself in Settings:

- **Themes.** A theme is a file with color tokens and any CSS, so it sets colors, typefaces,
  shapes, spacing and layout. Six ship with the app.
- **Home.** Which cards are shown, in what order and how wide.
- **Menu.** The items, their icons and the screens they open, including screens of your modules.
  The menu can also be hidden.
- **Mascot.** Axiko the axolotl wears the theme's colors. Recolor it, give it other phrases,
  replace it with a character of your own, or turn it off. The chat button has three looks.
- **Details.** The floating button, the launcher icon, the interface scale, the language.
- **The agent.** Its prompt, tools and memory, the model for each role, and other agents beside it.
- **The screens themselves.** If a theme is not enough, ask for the chat or Home to work
  differently.

## Build and share modules

A module is a folder: `module.json`, a screen in `ui/index.html`, and when needed the logic in
`main.js`, a card for Home and dictionaries for other languages. There is no build step. You can
ask the agent to make one, edit it in the web hub's studio, or write it by hand.

Two rules keep modules usable by other people:

- A module works fully in English. Other languages, Russian included, are welcome and optional.
- A module colors itself with the kit's variables, so every theme suits it.

Start with the [module conventions](docs/modules.md), [native contracts](docs/native-contracts.md)
and [game development guide](docs/game-development.md). Each has a supplementary Russian version.
The app also ships detailed Russian references for its own agent:
[handbook.md](app/src/main/assets/factory/agent/handbook.md) and
[api.md](app/src/main/assets/factory/agent/api.md).

When a module is useful to you, it is probably useful to someone else. Share it:

- **The official catalog** is [Billerens/axiko-store](https://github.com/Billerens/axiko-store):
  about forty modules and games at the moment. Its README describes the package layout, versions and
  listings in several languages.
- **Your own repository.** Any HTTPS address or GitHub repository with an `index.json` is a store
  that others can connect in their app. The format is in
  [repository-format-v1.md](docs/marketplace/repository-format-v1.md), and the tools that build a
  catalog are in [scripts/store](scripts/store).
- **Mods.** Took someone's package and changed it? Publish the change as a
  [mod](docs/marketplace/mods.md) that lays over the original.

Tell people about Axiko, show what yours has turned into, and publish what you built.

> [!WARNING]
> **Trust is yours to give.** Axiko is a community. Anyone can publish a module or run a
> repository, and not everyone is honest.
>
> **An installed module is not sandboxed.** It runs with everything Axiko itself can do on your
> phone: your files and personal data, the network, and every permission you granted, which may
> include notifications and the screen of other apps. The permissions a package declares inform
> you; they do not restrict it. A malicious module could send your data out, read your messages,
> act in other apps in your name, or spend your model budget.
>
> What Axiko does before an install:
> - checks the package's structure and the hash of the archive and of every file;
> - shows a plan with what will change, what the package can reach and whether it starts by itself;
> - runs none of the package's code during the install;
> - lets the agent review the code against a [fixed list of rules](docs/marketplace/review-rules.md):
>   a plain violation bans that release, a suspicion is shown to you with the file and the line;
> - takes a snapshot, so the install can be rolled back.
>
> **A passed review is not proof of safety.** The reviewer is a language model. It can miss things
> and it can be fooled.
>
> What you should do:
> - install only from sources you trust, and treat an unknown repository as you would an unknown
>   APK;
> - read the plan and the warnings instead of tapping through them;
> - be wary of a module that wants notifications, the screen, the shell, secrets or the network
>   without an obvious reason;
> - keep a backup, and roll back or remove a module that behaves oddly.
>
> The details are in [implementation.md](docs/marketplace/implementation.md)
> ([Русский](docs/marketplace/implementation.ru.md)).

## Get started

On an arm64 phone running Android 11 or newer, download the APK from
[GitHub Releases](https://github.com/Billerens/axiko/releases/latest) and open it to install Axiko.
Android may ask you to allow installation from your browser or file manager.

To build Axiko yourself, install Android SDK platform 36 and JDK 17 or newer, then run:

```powershell
scripts\fetch-libs.ps1                 # once: the speech recognition library (49 MB)
.\gradlew.bat assembleDebug -Pphone    # arm64 only; drop -Pphone for an emulator
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Then open Settings in the app and follow the setup steps: add a model provider (any
OpenAI-compatible address and its key) or download a model to the phone.

Building, updating an older install, tests and scripts are covered in
[docs/development.md](docs/development.md) ([Русский](docs/development.ru.md)).

## Docs

English is the primary documentation language; Russian translations are supplementary.
The [documentation index](docs/README.md) ([Русский](docs/README.ru.md)) lists all current contracts.

| Topic | English | Russian |
|---|---|---|
| Runtime and application layers | [Architecture](docs/architecture.md) | [Русский](docs/architecture.ru.md) |
| Build, checks and contribution conventions | [Development](docs/development.md) | [Русский](docs/development.ru.md) |
| Module and game conventions | [Modules](docs/modules.md), [games](docs/game-development.md) | [Модули](docs/modules.ru.md), [игры](docs/game-development.ru.md) |
| Shell capabilities | [Native contracts](docs/native-contracts.md) | [Русский](docs/native-contracts.ru.md) |
| Package format and review policy | [Store documentation](docs/marketplace/README.md) | [Русский](docs/marketplace/README.ru.md) |
| Backup and restore | [Backup](docs/backup.md) | [Русский](docs/backup.ru.md) |
| Editable desktop interface | [Web hub](docs/web-hub.md) | [Русский](docs/web-hub.ru.md) |
| System assistant panel | [On-screen assistant](docs/assistant.md) | [Русский](docs/assistant.ru.md) |
| Mascot and themes | [Brand contracts](docs/brand.md) | [Русский](docs/brand.ru.md) |
| README video sources and renderer | [Promo assets](assets/promo/README.md) | [Русский](assets/promo/README.ru.md) |

## License

Axiko's own code, including its factory modules, is licensed under the
[Apache License, Version 2.0](LICENSE). Third-party components carry their own licenses.
For example, the APK bundles the PRoot executable under GPL-2.0.
