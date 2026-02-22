# PhoneAgent 🤖

An autonomous AI agent for Android that controls your phone using AI vision and accessibility services. Powered by NVIDIA's free API with the Kimi K2.5 model.

---

## What It Does

PhoneAgent lets you give natural language commands and the app autonomously:
- Sees your phone screen via screenshot
- Decides what to tap, type, scroll
- Executes the action using Accessibility Services
- Verifies the result and repeats until done

**Example Commands:**
- "Open YouTube and search for lofi music"
- "Send a WhatsApp message to Mom saying I'll be late"
- "Turn on WiFi"
- "Open Settings and set brightness to 50%"
- "Search Google for weather in New York"
- "Open my email and read the latest unread message"

---

## Getting Your Free NVIDIA API Key

1. Go to **https://build.nvidia.com**
2. Click "Sign In" or "Get Started" (free account)
3. Navigate to **API** in the left menu
4. Click **"Get API Key"**
5. Copy your key (starts with `nvapi-`)

The free tier includes sufficient credits to run PhoneAgent extensively.

---

## Installation

### Option A: Build from Source (Android Studio)

**Requirements:**
- Android Studio Hedgehog (2023.1) or newer
- Android SDK 34
- JDK 17

**Steps:**
1. Clone or download this project
2. Open Android Studio → Open an Existing Project → select the `PhoneAgent` folder
3. Wait for Gradle sync to complete
4. Connect your Android phone (USB debugging enabled) or use an emulator
5. Click **Run** (▶) to build and install

**Enable USB Debugging on your phone:**
- Settings → About Phone → tap "Build number" 7 times
- Settings → Developer Options → enable "USB Debugging"

### Option B: Build APK

1. Open the project in Android Studio
2. Build → Generate Signed Bundle/APK → APK
3. Choose debug for testing or release for production
4. Transfer the APK to your phone and install it
5. Allow "Install from unknown sources" when prompted

---

## First-Time Setup (Permissions)

On first launch, PhoneAgent walks you through 5 setup steps:

### Step 1: Accessibility Service *(Required)*
- Tap "Open Accessibility Settings"
- Find "PhoneAgent" in the list
- Toggle it ON
- Confirm the dialog
- Return to PhoneAgent

**Why needed:** This is how the agent taps buttons, types text, and scrolls.

### Step 2: Notification Access *(Recommended)*
- Tap "Open Notification Settings"
- Find "PhoneAgent" and enable it
- Return to PhoneAgent

**Why needed:** Allows reading notifications as context for tasks.

### Step 3: Screen Recording *(Strongly Recommended)*
- Tap "Enable Screen Capture"
- Allow the recording permission dialog
- The agent can now see the screen with AI vision

**Why needed:** Without this, the agent operates blind (text-only mode via accessibility tree). Vision dramatically improves accuracy.

### Step 4: Microphone *(Optional)*
- Tap "Allow Microphone"
- Accept the permission

**Why needed:** Enables voice commands via the microphone button.

### Step 5: API Key *(Required)*
- Paste your NVIDIA API key (starts with `nvapi-`)
- Tap "Save API Key"

Once all required permissions are granted, tap **"Continue to PhoneAgent"**.

---

## Usage

### Text Commands
1. Type your command in the bottom input bar
2. Tap the send button (➤)
3. Watch the agent work in real-time

### Voice Commands
1. Tap the microphone button (🎙)
2. Speak your command clearly
3. The agent starts working automatically

### Canceling a Task
- Tap the **✕ Cancel** button that appears during execution

### Settings
- Tap the gear icon (⚙️) in the top right
- Configure API key, model, max steps, voice response, etc.

---

## Settings Reference

| Setting | Description | Default |
|---------|-------------|---------|
| NVIDIA API Key | Your free API key from build.nvidia.com | Empty |
| Model ID | AI model to use | moonshotai/kimi-k2.5 |
| Agent Name | Display name in the UI | PhoneAgent |
| Thinking Mode | Kimi reasons before each action (more accurate, slightly slower) | ON |
| Voice Response | Speak task results aloud | OFF |
| Max Steps | Maximum actions per task (5-20) | 15 |
| Background Check | How often to check scheduled tasks | 30 min |

---

## How It Works (Technical)

```
User gives command
       ↓
Screenshot captured (MediaProjection)
       ↓
Screenshot + command sent to Kimi K2.5 (NVIDIA API)
       ↓
Kimi analyzes screen, decides action
       ↓
Action executed (AccessibilityService)
       ↓
Wait 1.5 seconds for screen update
       ↓
New screenshot → repeat until done or max steps reached
```

### AI Response Format
Kimi always responds in structured format:
```
SCREEN: what is currently visible
ACTION: tap | type | scroll_up | scroll_down | open_app | done | failed
TARGET: element to interact with
TEXT: text to type (if applicable)
PACKAGE: app package name (if opening app)
REASON: why this action
COMPLETE: yes | no
```

---

## Privacy & Security

- **Your API key is stored locally** in Android SharedPreferences, never sent anywhere except the NVIDIA API
- **Screenshots are only sent to NVIDIA** to process your commands — they are not stored
- **Conversation history** is stored locally in a Room database on your device
- **No analytics or tracking** — this is fully open source

---

## Troubleshooting

**"Accessibility Service not enabled" banner appears:**
→ Go to Settings → Accessibility → PhoneAgent → toggle ON

**Agent can't find elements:**
→ Ensure screen recording is enabled for vision
→ Try describing the element more precisely
→ Some apps block accessibility services (banking apps, etc.)

**API errors:**
→ Check your API key in Settings
→ Verify internet connection
→ NVIDIA free tier has rate limits — wait a moment and retry

**App crashes or freezes:**
→ Check that all required permissions are granted
→ Restart the app
→ Clear app data if issues persist (Settings → Apps → PhoneAgent → Clear Data)

**Voice input not working:**
→ Ensure microphone permission is granted
→ Check that your device has Google Speech Recognition installed

---

## Architecture

```
com.phoneagent/
├── AppController.kt          — Application class, singleton DB access
├── AgentLoop.kt              — Main agent execution loop
├── api/
│   └── KimiApiClient.kt      — NVIDIA API integration with vision
├── data/
│   └── ConversationMemory.kt — Room DB entities, DAOs, memory manager
├── services/
│   ├── AgentAccessibilityService.kt — Phone control via accessibility
│   ├── ScreenCaptureService.kt      — Screenshot via MediaProjection
│   └── NotificationListener.kt     — Read notifications
├── ui/
│   ├── MainActivity.kt             — Chat interface
│   ├── PermissionSetupActivity.kt  — Onboarding
│   ├── SettingsActivity.kt         — Configuration
│   └── ChatAdapter.kt              — RecyclerView adapter
└── utils/
    ├── VoiceEngine.kt              — Speech recognition + TTS
    ├── PermissionManager.kt        — Permission checking
    ├── TaskScheduler.kt            — WorkManager background tasks
    └── BootReceiver.kt             — Restart on boot
```

---

## Example Commands to Try

```
"Open YouTube and search for lofi hip hop"
"Go to my WhatsApp and send John a message saying running 10 minutes late"
"Open Settings and enable Do Not Disturb"
"Search Google for the current weather"
"Open my Gmail and read the latest email"
"Set an alarm for 7am tomorrow"
"Take a screenshot and show it to me"
"Open the calculator and compute 15% of 847"
"Go to Netflix and resume what I was watching"
"Open Spotify and play some jazz"
```

---

## License

MIT License — free to use, modify, and distribute.

---

## Credits

- **AI**: NVIDIA NIM API + Kimi K2.5 by MoonshotAI
- **Android**: Accessibility Services, MediaProjection, WorkManager, Room
- **UI**: Material Design 3 Dark Theme
