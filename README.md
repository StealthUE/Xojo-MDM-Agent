MDM App for Android
Mobile Device Management (MDM) app for provisioning and controlling Android devices. Local capabilities included; proprietary server code removed for open-source sharing. Easily expandable.
Features
Device Admin Management

Enable/disable device admin
Handle password changes, failures, expiration
Manage lock task mode (kiosk mode)

Service Enforcement

Enforce Wi-Fi, GPS, mobile data activation
Periodic service state checks via WorkManager

Permission Management

Auto-grant permissions (location, Wi-Fi, accounts) for device owner apps
Disable auto-revoke permissions
Request permissions (e.g., READ_PHONE_STATE)

Policy Enforcement

Apply JSON policies (disable camera, screen lock timeout, lock/unlock, wipe data)
Manage kiosk mode (enter/exit)
Silent app install/uninstall (device owner) or with prompts
Configure Wi-Fi networks
Set password policies (complexity, length)
Hide apps, restrict permissions
Manage Factory Reset Protection (FRP) with Google account
Restrict Bluetooth, USB file transfer, system updates
Clear notifications
Lock/unlock screen rotation
Enable/disable apps
Enable USB debugging, authorize computers
Open specific apps, set system theme (dark/light)

Device Information

Retrieve serial number, IMEI with retries
List installed user apps, filter MDM-installed apps

Provisioning

Handle profile provisioning completion
Initialize reset password token
Apply FRP policy during provisioning

Kiosk Mode

Launch/exit via SAKioskLauncherActivity, SAKioskExitActivity
Persist settings across reboots

Installation Management

Install APKs from URLs with progress tracking
Handle installation success/failure, user action prompts
Manage concurrent installations with latch

Logging and Broadcasting

Log events/errors to Xojo via broadcastToXojo
Optional file logging

Security

Manage reset password tokens
Grant secure settings permissions (e.g., WRITE_SECURE_SETTINGS)

Network and System Settings

Configure Wi-Fi via suggestions (API 29+)
Set system update policies (freeze/windowed)
Adjust screen timeout, keyguard settings

User Profile Management

Log user profiles
Handle multi-user environments for app visibility

Planned Features

Desktop app for QR code provisioning
Screenshots of current system

Notes

Maintains consistent TCP connection to server (foreground)
Periodic API check when TCP is down (not included in open-source)
Interfaces with AAR library for core functionality

