# The engine and apksig use no reflection. If R8 ever reports missing optional classes
# from apksig, silence them here rather than keeping the whole library.
-dontwarn javax.annotation.**
-dontwarn org.bouncycastle.**
