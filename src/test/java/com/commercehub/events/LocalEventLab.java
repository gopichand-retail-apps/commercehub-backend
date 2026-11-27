package com.commercehub.events;

/** Test-only local runner. Never packaged into the application artifact. */
public final class LocalEventLab {
  private LocalEventLab() {}
  public static void main(String[] arguments) throws Exception {
    new ProcessPipelineTest().interactive();
  }
}
