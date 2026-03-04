package com.appmstudio.bletutorial.domain.model

sealed class BondState {
    data object NotBonded : BondState()
    data object Bonding : BondState()
    data object Bonded : BondState()
}
