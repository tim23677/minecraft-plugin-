package dev.orderhub.tx;

/** Art der wirtschaftlichen Operation, die ein Journal-Eintrag beschreibt. */
public enum TxType {
  ESCROW_CREATE,
  DELIVERY,
  CANCEL_REFUND,
  EXPIRE_REFUND,
  CLAIM,
  ITEM_RETURN,
  ADMIN_ADJUST
}
