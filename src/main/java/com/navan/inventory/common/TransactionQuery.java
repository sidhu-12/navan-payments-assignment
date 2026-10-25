package com.navan.inventory.common;

import java.util.List;
import java.util.Optional;

public interface TransactionQuery {

    Optional<TransactionSnapshot> findSnapshot(String id);

    List<TransactionSnapshot> findSnapshots(List<String> ids);

    List<TransactionSnapshot> lockSnapshots(List<String> ids);
}
