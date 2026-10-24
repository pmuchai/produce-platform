package com.primeroot.platform.security;

import com.primeroot.platform.access.AccessDtos.BranchResponse;
import com.primeroot.platform.access.AccessDtos.UserResponse;
import com.primeroot.platform.access.BranchService;
import com.primeroot.platform.access.Permission;
import com.primeroot.platform.access.StaffService;
import com.primeroot.platform.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

@RestController
public class HomeController {
	private final StaffService staff;
	private final BranchService branches;
	private final AuditService audit;

	public HomeController(StaffService staff, BranchService branches, AuditService audit) {
		this.staff = staff;
		this.branches = branches;
		this.audit = audit;
	}

	@GetMapping(value = "/home", produces = MediaType.TEXT_HTML_VALUE)
	public String home(HttpServletRequest request, CsrfToken csrf) {
		var current = staff.me();
		var permissions = current.permissions();
		var global = current.staff().allBranches();
		var base = request.getContextPath();
		var cards = new StringBuilder();
		if (permissions.contains(Permission.BRANCH_VIEW)) {
			var visible = branches.list();
			var active = visible.stream().filter(BranchResponse::active).count();
			cards.append(card("Branches", visible.size() + " visible",
				active + " active branches within your access scope.", base + "/api/branches"));
		}
		if (global && permissions.contains(Permission.USER_VIEW)) {
			var users = staff.list();
			var active = users.stream().filter(UserResponse::active).count();
			cards.append(card("Staff access", users.size() + " approved",
				active + " active staff members across the business.", base + "/api/users"));
		}
		if (global && permissions.contains(Permission.AUDIT_VIEW)) {
			var events = audit.list();
			cards.append(card("Audit activity", events.size() + " recent events",
				"Latest up to 100 administrative and sign-in events.", base + "/api/audit-events"));
		}
		if (cards.isEmpty()) {
			cards.append("""
				<section class="card"><h2>Your workspace is ready</h2>
				<p>No summary dashboards are available for your current permissions and branch scope.
				Contact your administrator if you need additional access.</p></section>
				""");
		}
		var capabilities = permissions.stream().sorted()
			.map(permission -> "<li>" + escape(permission.name()) + "</li>")
			.collect(java.util.stream.Collectors.joining());
		if (capabilities.isEmpty()) {
			capabilities = "<li>No business permissions assigned</li>";
		}
		return """
			<!doctype html>
			<html lang="en">
			<head>
			  <meta charset="utf-8">
			  <meta name="viewport" content="width=device-width, initial-scale=1">
			  <title>Home | Produce Platform</title>
			  <style>
			    :root { font-family: system-ui, sans-serif; color: #17372b; background: #f3f7f4; }
			    body { margin: 0; }
			    header { background: #173f30; color: white; padding: 1.25rem max(1rem, calc((100vw - 1120px)/2)); }
			    nav { display: flex; align-items: center; justify-content: space-between; gap: 1rem; }
			    main { max-width: 1120px; margin: 2rem auto; padding: 0 1rem; }
			    h1 { font-size: clamp(1.6rem, 4vw, 2.4rem); margin-bottom: .5rem; }
			    h2 { font-size: 1.15rem; }
			    p { line-height: 1.6; }
			    .scope { color: #496254; }
			    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%%, 260px), 1fr)); gap: 1rem; margin: 2rem 0; }
			    .card { background: white; border: 1px solid #d9e5dc; border-radius: .75rem; padding: 1.5rem; }
			    .metric { font-size: 1.8rem; font-weight: 700; margin: 1rem 0; }
			    a { color: #176745; }
			    button { cursor: pointer; background: white; color: #173f30; border: 0; border-radius: .35rem; padding: .65rem 1rem; font: inherit; }
			    a:focus-visible, button:focus-visible { outline: 3px solid #d99c20; outline-offset: 4px; }
			    ul { display: flex; flex-wrap: wrap; gap: .5rem; list-style: none; padding: 0; }
			    li { background: #e3ede5; border-radius: .3rem; padding: .5rem; }
			  </style>
			</head>
			<body>
			  <header><nav aria-label="Main"><strong>Produce Platform</strong>
			    <form action="%s/api/auth/logout" method="post">
			      <input type="hidden" name="%s" value="%s">
			      <button type="submit">Sign out</button>
			    </form>
			  </nav></header>
			  <main>
			    <h1>Welcome, %s</h1>
			    <p class="scope">Access scope: %s</p>
			    <p>Your operational overview, based on your Keycloak app permissions.</p>
			    <div class="grid" aria-label="Summary dashboards">%s</div>
			    <section aria-label="Your access"><h2>Your effective permissions</h2><ul>%s</ul>
			      <p>Dashboards appear as permissions and implemented modules become available.
			      Sales, inventory and finance summaries are not available yet.</p>
			    </section>
			  </main>
			</body>
			</html>
			""".formatted(escape(base), escape(csrf.getParameterName()), escape(csrf.getToken()),
				escape(current.staff().fullName()), global ? "Business-wide" : "Assigned active branches",
				cards, capabilities);
	}

	private static String card(String title, String metric, String description, String url) {
		return """
			<section class="card"><h2>%s</h2><p class="metric">%s</p><p>%s</p>
			<a href="%s">View details (JSON)</a></section>
			""".formatted(escape(title), escape(metric), escape(description), escape(url));
	}

	private static String escape(String value) {
		return HtmlUtils.htmlEscape(value);
	}
}
