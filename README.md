# Naive Java OCI Image Builder
Simple use cases of building images do not require particular tooling or a heap of knowledge. This code shall prove that it is actually not difficult to make images.

## Use Case
This example expects a custom JRE built with 'jlink' and a java application to run in it.

## Prerequisites
You will need "tar" and "[crane](https://github.com/google/go-containerregistry/blob/main/cmd/crane/README.md)" on the PATH. "Wait, is 'crane' not a special tool?" you might be wondering. It is. We need it to interact with OCI registries, which is not the feature to be proven feasible. Concretely, downloading a layer of the "distroless"-image by Google. This particular base image gets us 'libc', SSL certificates and some things we absolutely need from an OS to run the JVM.

## Usage
### Run
```fish
javac src/***.java -d out
cd out/
java OCIImageBuilder --jre ./custom-jre --app ./app-layer --module com.example.helloworld
```
### Test
```fish
javac src/***.java -d out
cd out/com/assense/ociimagebuilder
java OCIImageBuilderTest
```

## Load the resulting Image
While the result complies with OCI, it does not comply with e.g. podman and docker expectations. Therefore one has to archive the result.

```bash
tar -cf oci-image.tar -C oci-image .
podman image pull oci-archive:$(pwd)/oci-image.tar:latest
```