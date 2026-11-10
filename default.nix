{ pkgs ? import <nixpkgs> { } }:

pkgs.maven.buildMavenPackage {
  pname = "jcstress";
  version = "0.17-SNAPSHOT";
  src = pkgs.lib.cleanSource ./.;

  nativeBuildInputs = [ pkgs.git ];
  mvnJdk = pkgs.openjdk17;
  mvnParameters = "-pl litmus-tests -am -Dmaven.javadoc.skip=true";
  mvnHash = "sha256-zpc5D+HEuY48djiT52ttoHt7aPlXkGc7MelrElXYA/w=";
  doCheck = false;

  installPhase = ''
    runHook preInstall
    mkdir -p "$out"
    cp litmus-tests/target/jcstress.jar "$out/jcstress.jar"
    runHook postInstall
  '';
}
