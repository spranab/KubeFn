// kubefn-contracts: Shared type definitions for HeapExchange objects.
//
// This module defines the "contract" between functions — the types
// that get published to and consumed from the HeapExchange.
//
// Every function depends on this module (compileOnly) to know
// what objects exist on the heap and what shape they have.
//
// This is the KubeFn equivalent of Protobuf definitions in microservices.

plugins {
    `java-library`
}

dependencies {
    // No dependencies — pure data types only
}
