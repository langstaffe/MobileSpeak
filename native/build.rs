fn main() {
    println!("cargo:rerun-if-changed=src/audio_ffi.cc");
    cc::Build::new()
        .cpp(true)
        .file("src/audio_ffi.cc")
        .flag_if_supported("-std=c++11")
        .compile("mobilespeak_audio_ffi");
}
