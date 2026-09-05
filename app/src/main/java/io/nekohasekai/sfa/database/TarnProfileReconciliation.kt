package io.nekohasekai.sfa.database

/** The row-set commit point, kept separate so real Room rollback can be fault-tested. */
internal fun reconcileProfiles(database: ProfileDatabase, add: List<Profile>, update: List<Profile>, remove: List<Profile>) {
    database.runInTransaction {
        val dao = database.profileDao()
        if (add.isNotEmpty()) {
            val ids = dao.insert(add)
            add.forEachIndexed { index, profile -> profile.id = ids[index] }
        }
        if (update.isNotEmpty()) dao.update(update)
        if (remove.isNotEmpty()) dao.delete(remove)
    }
}
