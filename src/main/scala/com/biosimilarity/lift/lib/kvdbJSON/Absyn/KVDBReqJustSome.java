package com.biosimilarity.lift.lib.kvdbJSON.Absyn; // Java Package generated by the BNF Converter.

public class KVDBReqJustSome extends ReqJust {
  public final UUID uuid_;

  public KVDBReqJustSome(UUID p1) { uuid_ = p1; }

  public <R,A> R accept(com.biosimilarity.lift.lib.kvdbJSON.Absyn.ReqJust.Visitor<R,A> v, A arg) { return v.visit(this, arg); }

  public boolean equals(Object o) {
    if (this == o) return true;
    if (o instanceof com.biosimilarity.lift.lib.kvdbJSON.Absyn.KVDBReqJustSome) {
      com.biosimilarity.lift.lib.kvdbJSON.Absyn.KVDBReqJustSome x = (com.biosimilarity.lift.lib.kvdbJSON.Absyn.KVDBReqJustSome)o;
      return this.uuid_.equals(x.uuid_);
    }
    return false;
  }

  public int hashCode() {
    return this.uuid_.hashCode();
  }


}