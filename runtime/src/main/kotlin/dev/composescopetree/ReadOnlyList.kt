package dev.composescopetree

/** Copy the caller's data into a list with no mutable public view. */
internal fun <T> readOnlyList(elements: Collection<T>): List<T> =
    object : AbstractList<T>(), RandomAccess {
        private val values = elements.toList()

        override val size: Int get() = values.size
        override fun get(index: Int): T = values[index]
    }
