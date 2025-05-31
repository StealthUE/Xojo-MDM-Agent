# Xojo-MDM-Agent

I’ve create an MDM (Mobile Device Management) App. It allows you to provision an Android device and control it. I’ll add a desktop app to generate the QR for provisioning. I’ll also add some screenshots of the current system. I have had to remove proprietary code so it is a little bare in its functionality back to the server. I have only included the local capabilities. This project can be easily expanded. It is able to run in the foreground and keep a consistent TCP connection to a server. In my version I also added a periodic API check when the TCP connection is down.

It interfaces with an AAR Library with the following functions;

Device Admin Management:
Enable/disable device admin.
Handle password changes, failures, and expiration.
Manage lock task mode (kiosk mode).
Service Enforcement:
Enforce Wi-Fi, GPS, and mobile data activation.
Periodically check and maintain service states via WorkManager.
Permission Management:
Auto-grant permissions (e.g., location, Wi-Fi, accounts) for device owner apps.
Disable auto-revoke permissions.
Request permissions (e.g., READ_PHONE_STATE).
Policy Enforcement:
Apply policies from JSON (e.g., disable camera, set screen lock timeout, lock/unlock device, wipe data).
Manage kiosk mode (enter/exit).
Install/uninstall apps silently (device owner) or with user prompts.
Configure Wi-Fi networks.
Set password policies (complexity, minimum length).
Hide apps or restrict permissions.
Manage Factory Reset Protection (FRP) with Google account.
Restrict Bluetooth, USB file transfer, or system updates.
Clear notifications.
Lock/unlock screen rotation.
Enable/disable apps.
Enable USB debugging and authorize computers.
Open specific apps or set system theme (dark/light).
Device Information:
Retrieve device serial number (SN) and IMEI with retries.
List installed user apps, filtering MDM-installed apps.
Provisioning:
Handle profile provisioning completion.
Initialize reset password token.
Apply FRP policy during provisioning.
Kiosk Mode:
Launch and exit kiosk mode via dedicated activities (SAKioskLauncherActivity, SAKioskExitActivity).
Persist kiosk settings across reboots.
Installation Management:
Install APKs from URLs with progress tracking.
Handle installation completion (success/failure, user action required).
Manage concurrent installations with a latch mechanism.
Logging and Broadcasting:
Log events and errors to Xojo via broadcastToXojo.
Optionally log to a file.
Security:
Manage reset password tokens for unlocking.
Grant secure settings permissions (e.g., WRITE_SECURE_SETTINGS).
Network and System Settings:
Configure Wi-Fi via suggestions (API 29+).
Set system update policies (freeze or windowed).
Adjust screen timeout and keyguard settings.
User Profile Management:
Log user profiles.
Handle multi-user environments for app visibility.
