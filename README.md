# Priperi — Firebase Auth & Admin Panel System

![Status](https://img.shields.io/badge/Status-Under_Development-orange)
![Platform](https://img.shields.io/badge/Platform-Android_Java-brightgreen)
![Backend](https://img.shields.io/badge/Backend-Firebase_Auth-FFCA28)
![Admin](https://img.shields.io/badge/Admin-Dedicated_Control_Panel-purple)

**Priperi** is an Android ecosystem consisting of two dedicated applications:
1. **Client Application (`priperi`)**: For end-user login, authentication, and session handling.
2. **Admin Panel Application (`Adminpanel`)**: A dedicated administrative tool for creating, registering, and provisioning user credentials.

---

## 🛠️ System Architecture

```text
                               ┌────────────────────────────────┐
                               │     Firebase Console / Auth    │
                               │  (Project ID: Loginsysaarush)  │
                               └──────────────┬─────────────────┘
                                              │
                       ┌──────────────────────┴──────────────────────┐
                       │                                             │
                       ▼                                             ▼
          ┌──────────────────────────┐                  ┌──────────────────────────┐
          │     Admin Panel App      │                  │        Client App        │
          │       (Adminpanel)       │                  │        (priperi)         │
          ├──────────────────────────┤                  ├──────────────────────────┤
          │ • Create user accounts   │                  │ • User authentication    │
          │ • Push credentials       │                  │ • Password show/hide     │
          │ • Provision access       │                  │ • Animated touch feedback│
          └──────────────────────────┘                  │ • Local session tracking │
                                                        └──────────────────────────┘
```

---

##  Admin Panel App (`Adminpanel`)

The **Admin Panel** is a separate, dedicated administrative utility designed for managing user onboarding and system control without exposing sign-up logic inside the client application.

### Key Capabilities:
- **User Account Provisioning**: Uses Firebase Auth `createUserWithEmailAndPassword` to create and register authorized user accounts directly into the `Loginsysaarush` database.
- **Credential Dispatch ("Push")**: Admin enters target user email (`mail_id`) and password (`usr_pass`) to register new accounts.
- **Access Control Isolation**: Ensures client app users cannot create accounts independently; all valid credentials must be issued through the Admin Panel.

---

##  Client App (`priperi`)

The **Client Application** provides a polished, interactive login interface for end users.

### Key Features:
- **Firebase Login**: Authenticates existing accounts using `signInWithEmailAndPassword`.
- **Interactive UI & Animations**:
  - Touch-scale spring effect on the login button (`MotionEvent`).
  - Animated multi-stage loading progress indicator (`Please Wait.`, `Please Wait..`, `Please Wait...`).
  - Toggle password visibility with an eye icon.
- **Session Management**:
  - Remembers logged-in user state via `SharedPreferences`.
  - Displays authenticated dashboard in `AfterActivity` with session timestamp calculations (+2h 30m).
  - Exit confirmation alert dialog on back-button press.

---

##  Repository Structure

```text
priperi/
├── priperi/                # Client Android Application
│   ├── app/src/main/
│   │   ├── java/com/priperi/
│   │   │   ├── MainActivity.java      # Client login & UI logic
│   │   │   └── AfterActivity.java     # User dashboard & session screen
│   │   └── res/
│   │       ├── layout/                # main.xml, after.xml
│   │       └── values/secrets.xml     # Shared Firebase credentials
│   └── build.gradle
├── Adminpanel/             # Admin Panel Android Application
│   ├── app/src/main/
│   │   ├── java/com/adminpanel/
│   │   │   └── MainActivity.java      # Admin provisioning screen
│   │   └── res/
│   │       ├── layout/main.xml        # Admin layout
│   │       └── values/secrets.xml     # Shared Firebase credentials
│   └── build.gradle
├── priperi_1.0.apk         # Pre-compiled Client Application APK
└── Adminpanel_1.0.apk      # Pre-compiled Admin Panel APK
```

---

##  Security Analysis & Firebase Rules

> [!IMPORTANT]
> **API Keys & Firebase Authentication**:
> Firebase API keys embedded in client applications are public project identifiers. Anyone with the API key can make requests to Firebase API endpoints unless access is strictly restricted on the backend.

### Recommended Admin & Client Hardening:
1. **Disable Public Sign-Up in Firebase**: Turn off self-registration in the Firebase Console so accounts can only be created via the Admin Panel.
2. **Role-Based Access Control (RBAC)**: Tag Admin Panel user accounts with custom claims (`admin: true`) via Firebase Admin SDK.
3. **App Check Protection**: Enable Firebase App Check (Play Integrity) to enforce that only verified APK builds of `priperi` and `Adminpanel` can interact with Firebase services.

---

##  Build Artifacts
Pre-built APKs are included in the project root:
- [Adminpanel_1.0.apk](file:///Users/aarushsharma/priperi/priperi/Adminpanel_1.0.apk): Admin Control Panel APK
- [priperi_1.0.apk](file:///Users/aarushsharma/priperi/priperi/priperi_1.0.apk): Client User App APK
