package nl.rowendu;

@FunctionalInterface
interface CancellationToken {
  boolean isCancelled();
}
