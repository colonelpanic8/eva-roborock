{
  description = "Roborock extension for EVA";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = {
    nixpkgs,
    flake-utils,
    ...
  }:
    flake-utils.lib.eachSystem ["x86_64-linux"] (system: let
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };
      buildToolsVersion = "37.0.0";
      cmdLineToolsVersion = "22.0";
      androidComposition = pkgs.androidenv.composeAndroidPackages {
        cmdLineToolsVersion = cmdLineToolsVersion;
        toolsVersion = "26.1.1";
        platformToolsVersion = "37.0.1";
        buildToolsVersions = [buildToolsVersion];
        platformVersions = ["37"];
        includeEmulator = false;
        includeSources = false;
        includeSystemImages = false;
        includeNDK = false;
        useGoogleAPIs = false;
        useGoogleTVAddOns = false;
      };
      androidHome = "${androidComposition.androidsdk}/libexec/android-sdk";
      aapt2 = "${androidHome}/build-tools/${buildToolsVersion}/aapt2";
      androidShell = pkgs.mkShell {
        packages = with pkgs; [
          androidComposition.androidsdk
          git
          jdk17
          just
        ];
        ANDROID_HOME = androidHome;
        ANDROID_SDK_ROOT = androidHome;
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${aapt2}";
        JAVA_HOME = pkgs.jdk17.home;
        shellHook = ''
          export PATH=${androidHome}/platform-tools:${androidHome}/cmdline-tools/${cmdLineToolsVersion}/bin:$PATH
        '';
      };
    in {
      devShells = {
        android = androidShell;
        default = androidShell;
      };
      formatter = pkgs.alejandra;
    });
}
