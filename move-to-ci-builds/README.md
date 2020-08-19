This folder contains scripts to adapt the builds.a.o links to the new
https://ci-builds.apache.org/ service.

Such links are typically found in our modules README files.

To test this use

    ./change-links.sh < input.md > /tmp/out
    diff /tmp/out expected-output.md 

And if that works for you, process a README with

    <full-path-to>/change-links.sh < README.md > /tmp/$$ && mv /tmp/$$ README.md