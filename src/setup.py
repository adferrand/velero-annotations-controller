import setuptools

setuptools.setup(
    name="velero-annotations-controller",
    version="0.0.1",
    author="Adrien Ferrand",
    author_email="ferrand.ad@gmail.com",
    description="A controller to synchronize Velero annotations on Pods.",
    url="https://github.com/adferrand/velero-annotations-controller",
    packages=setuptools.find_packages(),
    classifiers=[
        "Programming Language :: Python :: 3",
        "License :: OSI Approved :: MIT License",
        "Operating System :: OS Independent",
    ],
    python_requires='>=3.7',
    install_requires=['kopf==0.25', 'kubernetes==10.0.1'],
)
