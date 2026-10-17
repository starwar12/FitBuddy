# FitBuddy Architecture & Data Flow Diagrams
**Document Type:** Technical Architecture Specification & Privacy Threat Modeling Artifact  
**Target Audience:** Security Architects, Data Protection Officers (DPO), and C-Level Executives  
**Scope:** FitBuddy Android Application (`com.anant.fitbuddy`)

---

## 1. High-Level System Architecture (Abstract Overview)

This high-level diagram illustrates the primary components of the FitBuddy ecosystem, distinguishing between on-device operations, unencrypted local persistence, and external cloud services.

```mermaid
flowchart LR
    User["End User"] -->|"Photos, Text Logs, Metrics"| App["FitBuddy Android Client\n(Jetpack Compose UI & Business Logic)"]

    subgraph LocalPersistence ["Local Persistence (On-Device Sandbox)"]
        Storage[("Unencrypted Storage\n- Room SQLite DB (fitness_tracker_db)\n- Preferences DataStore (API keys, PII)")]
    end

    subgraph CloudEcosystem ["External Cloud Ecosystem"]
        LLM["Third-Party AI Gateways\n- Google Gemini API\n- OpenRouter API\n- OpenAI / Custom Gateways"]
        Telemetry["Monitoring & Diagnostics\n- Sentry.io Direct Ingest\n- Vercel Serverless Proxy"]
        CloudBackup["Backup & Storage Services\n- Google Drive (Auto-Backup)\n- MongoDB Atlas Cloud (via Proxy)"]
    end

    App <-->|"Read / Write Records"| Storage
    App -->|"1. Multimodal Prompts & Health State"| LLM
    App -->|"2. Daily Heartbeat with PII"| Telemetry
    App -->|"3. Backup Archives with Device Identifiers"| CloudBackup
```

---

## 2. Multimodal AI Processing & Data Minimization Flow

This diagram illustrates the processing pipeline for food and workout logging, demonstrating that multimodal processing is delegated entirely to cloud-hosted Large Language Models (LLMs) rather than executed via Edge AI. Furthermore, it highlights the structural violation of the Data Minimization Principle (GDPR Art. 5(1)(c)), wherein extensive medical and biometric data are bundled into food analysis prompts.

```mermaid
flowchart TD
    PhotoInput["Food Photograph"] --> ImgUtil["ImageUtils\n(Downsample to 768px, Compress to ~200KB JPEG)"]
    TextInput["Loose Text Description"] --> PromptCat["PromptCatalog\n(Payload Assembly)"]

    ProfileDB[("Room Database\n(UserProfile & BodyMeasurement Tables)")] --> ContextBuilder["MainViewModel.buildUserStateContext()\nExtracts: Age, Sex, Height, Weight,\nWaist, Hips, Body Fat %, Caloric Balances"]

    ImgUtil -->|"Base64 Encoded JPEG String"| PromptCat
    ContextBuilder -->|"Serialized User State JSON"| PromptCat

    PromptCat -->|"Consolidated Prompt Payload"| Dispatcher{"Provider Selection\n(AppSettings.kt)"}

    Dispatcher -->|"Provider: GEMINI"| GeminiAPI["Google Gemini API\n(generativelanguage.googleapis.com)"]
    Dispatcher -->|"Provider: OPENROUTER"| OpenRouterAPI["OpenRouter API\n(openrouter.ai/api/v1)"]
    Dispatcher -->|"Provider: CUSTOM / OPENAI"| CustomAPI["OpenAI / Custom Gateway\n(api.openai.com or Custom Base URL)"]
    Dispatcher -->|"No Key & Plain Text Only"| RegexFallback["Local Offline Simulator\n(Regex Rule-Based, No Vision Support)"]
```

---

## 3. Local Storage Persistence & Google Cloud Auto-Backup

This diagram models the data-at-rest layer, demonstrating the absence of application-level database encryption (SQLCipher) and highlighting the automatic exfiltration of health profiles and API credentials to Google Cloud via Android Auto Backup.

```mermaid
flowchart TD
    subgraph AndroidSandbox ["Android Application Sandbox (/data/data/com.anant.fitbuddy/)"]
        CoreApp["FitBuddy Core Engine"]
        
        RoomDB[("Room Database: fitness_tracker_db\n(Plaintext SQLite)\n- User Profile (Age, Sex, Weight, Goals)\n- Daily Food & Ingredient Logs\n- Body Measurements (Waist, Hip, Fat %)\n- Workout Sessions & Exercises")]
        
        DataStore[("DataStore: shared_prefs/app_settings.preferences_pb\n(Plaintext Protocol Buffers)\n- OpenRouter, Gemini, OpenAI API Keys\n- User Full Name (firstName, lastName)\n- Permanent Support ID (UUID)")]
    end

    CoreApp -->|"Execute SQL Queries (No Encryption)"| RoomDB
    CoreApp -->|"Read / Write Preferences (No Keystore Encryption)"| DataStore

    RoomDB -.->|"Automatic OS Sync\n(backup_rules.xml includes all databases)"| GoogleDrive[("Google Cloud Auto-Backup\n(User Google Account Storage)")]
    DataStore -.->|"Automatic OS Sync\n(SettingsDataStore placed in shared_prefs)"| GoogleDrive
```

---

## 4. Telemetry, Hardware Identifiers & Sinkhole Evasion

This diagram details the telemetry pipeline implemented in `CrashReporter.kt` and `SentryHeartbeatProxy.kt`. It documents how identity attributes (full name) are dispatched despite in-app claims of anonymity, and illustrates the mechanism designed to bypass network-level privacy filters (e.g., Pi-hole, AdGuard, corporate DNS sinkholes).

```mermaid
flowchart TD
    Scheduler["Heartbeat Trigger\n(HeartbeatScheduler Alarm / Application Init)"] --> Collector["CrashReporter.kt / HeartbeatInfo"]
    
    Collector --> Payload["Construct Telemetry Envelope\nAttributes include:\n- username: AppSettings.usernameForHeartbeat\n- support_id: Persistent UUID\n- record_count: Total logs count\n- device: Manufacturer, Model, Android SDK"]

    Payload --> DNSCheck{"Probe Ingest Reachability\n(HTTP HEAD to Sentry Ingest Host)"}

    DNSCheck -->|"Reachable (Direct Connection)"| SentryDirect["Direct Sentry.io Ingest\n(AsyncHttpTransportFactory)"]
    DNSCheck -->|"Unreachable (Blocked by DNS Sinkhole / Adblocker)"| VercelProxy["FitBuddy Vercel Serverless Proxy\n(SentryHeartbeatProxy.kt)"]

    VercelProxy -->|"Forward Serialized Envelope Server-Side"| SentryDirect
```

---

## 5. Inter-Process Communication (IPC) Vulnerability

This diagram outlines the vulnerability present in `MeasurementBridgeProvider.kt`. Because the `ContentProvider` is declared with `android:exported="true"` without defining an `android:permission` or enforcing cryptographic signature checks, unauthorized applications can extract historical biometric readings.

```mermaid
flowchart LR
    subgraph FitBuddyProcess ["FitBuddy Application Sandbox"]
        Provider["MeasurementBridgeProvider\n(android:exported=true)\n(No signature verification)"]
        MeasurementsTable[("Body Measurements Table\n(Weight, Body Fat %, Waist, Hips)")]
        Provider <--> MeasurementsTable
    end

    subgraph ClientApplications ["Collocated Applications on Device"]
        LegitClient["Legitimate Client App\n(Package: com.anant.freescale)"]
        RogueClient["Unauthorized / Malicious App\n(Declares Package: com.anant.freescale)"]
    end

    LegitClient -->|"IPC Call: method='exportAll'"| Provider
    RogueClient -->|"IPC Call: method='exportAll'\n(Passes package name string check)"| Provider
    Provider -.->|"Transfers Complete Historical Biometric Dataset"| RogueClient
```

---

## 6. Cloud Backup Cryptographic Key Separation Failure

This diagram documents the cryptographic key management flaw within the cloud backup subsystem (`FitnessRepository.kt` and `MongoBackupRepository.kt`). When a custom password is not supplied, the public `supportId` identifier is utilized as the key derivation passphrase, nullifying the confidentiality guarantees of AES-256-GCM.

```mermaid
flowchart TD
    FullBackup["Full Database Serialization\n(BackupManager.buildBackupData)"] --> KDF["PBKDF2-HMAC-SHA256\n(BackupCrypto.kt)"]

    subgraph KeyResolution ["Passphrase Resolution (FitnessRepository.kt)"]
        CheckPw{"Custom Password Set?"}
        CheckPw -->|"Yes"| CustomKey["Android Keystore Decrypted Password"]
        CheckPw -->|"No"| DefaultKey["Default Passphrase = supportId.toCharArray()"]
    end

    DefaultKey -->|"Passphrase Input"| KDF
    CustomKey -->|"Passphrase Input"| KDF

    KDF -->|"Derived 256-bit AES Key"| Cipher["AES-256-GCM Cipher"]
    FullBackup --> Cipher
    Cipher --> EncryptedDoc["Backup Document (JSON)"]

    EncryptedDoc --> PayloadAssembly["Assemble HTTP Body (MongoBackupRepository.kt)\n- payloadJson: Encrypted Ciphertext\n- supportId: Plaintext Document ID\n- chunkId: Plaintext Chunk Identifier\n- macId: Hardware MAC or Android ID\n- deviceName: Hardware Model"]

    PayloadAssembly -->|"HTTPS POST"| VercelMongoProxy["FitBuddy Vercel Backup Proxy\n(Holds MongoDB Atlas Connection)"]
    VercelMongoProxy --> MongoAtlas[("MongoDB Atlas Database")]
```

