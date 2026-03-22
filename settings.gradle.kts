rootProject.name = "kubefn"

include(
    "kubefn-api",
    "kubefn-runtime",
    "kubefn-operator",
    "kubefn-sdk",
    "examples:hello-function",
    "examples:checkout-pipeline"
)
