package my.simple.name

import my.Reproducer

fun test() = Reproducer()

fun main() {
    Reproducer<caret>.test().number()
}