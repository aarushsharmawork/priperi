# Legacy Firebase lockdown

The app uses Supabase for accounts and encrypted relay data. Firebase supplies optional generic push. These rules deny legacy Firestore, Realtime Database and Storage access. Replace the project placeholder in .firebaserc only if maintaining those services in your own project. See [cloud setup](../../../docs/CLOUD-SETUP.md).

[Developer roadmap](../../../docs/PIPELINE.md)
