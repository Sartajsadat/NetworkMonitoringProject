package gov.mot.netmon.common;

import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Builds a {@link Pageable} from untrusted query parameters, allowing only whitelisted
 * sort fields and capping the page size.
 */
public final class PageRequests {

    public static final int MAX_PAGE_SIZE = 200;

    private PageRequests() {
    }

    public static Pageable of(int page, int size, String sort, Set<String> allowedFields, Sort defaultSort) {
        if (page < 0) {
            throw ApiException.badRequest("INVALID_PAGE", "page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("INVALID_PAGE_SIZE", "size must be between 1 and " + MAX_PAGE_SIZE);
        }
        Sort resolved = defaultSort;
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            String field = parts[0].trim();
            if (!allowedFields.contains(field)) {
                throw ApiException.badRequest("INVALID_SORT",
                        "sort field must be one of " + String.join(", ", allowedFields));
            }
            Sort.Direction dir = parts.length > 1 && parts[1].trim().equalsIgnoreCase("desc")
                    ? Sort.Direction.DESC : Sort.Direction.ASC;
            resolved = Sort.by(dir, field);
        }
        return PageRequest.of(page, size, resolved);
    }
}
