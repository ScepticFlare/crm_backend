package com.compact.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

// A page of suppression entries or audit rows (items are one or the other).
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EmailSuppressionPage<T> {

    private List<T> items;
    private long total;
    private int page;
    private int size;
}
