# Komponen yang disebut dari manifest dipertahankan otomatis oleh R8.
# Aturan di bawah cuma mencegah build gagal karena referensi opsional.
-dontwarn org.json.**
-dontwarn javax.annotation.**

# Nama kelas service/receiver harus tetap utuh karena manifest memanggilnya
# dengan nama literal.
-keepnames class com.kai.mobilerat.C2Service
-keepnames class com.kai.mobilerat.BootReceiver
-keepnames class com.kai.mobilerat.MainActivity
