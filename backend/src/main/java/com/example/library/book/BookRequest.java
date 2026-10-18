package com.example.library.book;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record BookRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 160) String author,
        @NotBlank @Pattern(regexp = "^(?:\\d{10}|\\d{13})$", message = "ISBN must contain 10 or 13 digits")
        String isbn,
        @NotNull @Min(1000) @Max(9999) Integer publicationYear,
        @NotNull BookStatus status) {
}
