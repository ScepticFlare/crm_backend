# Compact CRM Backend

Backend service for the Compact CRM application.

Built with Java and Spring Boot, this backend provides REST APIs for authentication, authorization, lead management, opportunities, customers, follow-ups, employees, activity history, reporting, file handling, and email workflows.

The backend is deployed on Render and is used by the React frontend of the CRM application. The CRM is actively used internally by 10+ employees at Compact Systems.

## Overview

The backend handles the application's core business logic, authentication and authorization, database operations, and integrations with external services.

The main CRM workflow is:

Lead → Opportunity → Customer

The backend enforces business rules around these workflows while providing filtered, paginated REST APIs for the frontend.

## Architecture

The application follows a layered Spring Boot architecture:

Client / React Frontend
        ↓
REST Controllers
        ↓
Service Layer
        ↓
Repository Layer
        ↓
PostgreSQL Database

Controllers handle HTTP requests and responses.

Services contain application and business logic.

Repositories provide database access using Spring Data JPA and Hibernate.

## Authentication & Authorization

Authentication is implemented using Spring Security and JSON Web Tokens (JWT).

The authentication flow is:

Login
  ↓
Credentials validated
  ↓
JWT generated and signed
  ↓
Client sends Bearer token
  ↓
JWT authentication filter validates token
  ↓
Security context populated
  ↓
Protected endpoint accessed
  ↓
Authorization checks applied

The backend uses stateless authentication for protected REST endpoints.

### Roles

The CRM supports three primary employee roles:

- Admin
- Manager
- Employee

Authentication and authorization are handled separately.

Authentication determines the identity of the user, while authorization determines which operations and resources that user can access.

The application also supports scope-based access where applicable:

- Own
- Team
- All

Access-control logic is centralized so that authorization rules can be applied consistently across CRM operations.

## CRM Modules

### Lead Management

The backend supports:

- Lead creation and updates
- Lead status management
- Lead-to-opportunity conversion
- Lead products
- Follow-ups
- Activity history
- Search and filtering
- Server-side pagination
- Import and export functionality

### Opportunity Management

The opportunity service supports:

- Opportunity creation and updates
- Opportunity stage management
- Opportunity products
- Follow-ups
- Customer conversion
- Business-rule validation

The service layer also prevents invalid workflow transitions where required by the CRM's business rules.

### Customer Management

The backend manages customer records and their relationships with opportunities created through the CRM workflow.

### Follow-ups

Follow-up functionality allows employees to create, update, and track follow-up activities associated with CRM records.

### Employee Management

Employee functionality includes employee records, roles, reporting relationships, and access control required by the CRM.

### Activity History

The backend maintains activity records for important operations performed within the CRM.

Activity records can include:

- Employee who performed the action
- Action performed
- CRM module
- Entity
- Entity ID
- Description
- Timestamp

Activity history supports filtering, sorting, and server-side pagination.

## Pagination, Filtering & Search

The backend provides server-side pagination for supported list and history endpoints.

Where applicable, APIs support:

- Pagination
- Sorting
- Filtering
- Search

This allows the frontend to retrieve only the required records instead of loading complete datasets into the browser.

## Database

The application uses PostgreSQL as its relational database.

Persistence is handled through:

- Spring Data JPA
- Hibernate
- JPA entity relationships
- Repository-based data access

The database models relationships between the main CRM entities, including:

- Leads
- Opportunities
- Customers
- Employees
- Follow-ups
- Products
- Activity logs

## External Integrations

### Supabase Storage

Supabase Storage is used for file storage associated with CRM functionality.

The backend handles the required storage operations and provides the frontend with the appropriate access flow.

### Brevo

Email functionality is integrated through the Brevo HTTP API.

The backend communicates with Brevo through authenticated REST requests for email delivery.

## REST API

The backend exposes REST APIs for the major CRM modules, including:

- Authentication
- Leads
- Opportunities
- Customers
- Follow-ups
- Employees
- Activity logs
- Reports
- File operations
- Email operations

The APIs support the application's authentication, authorization, business logic, filtering, sorting, and pagination requirements.

## Project Structure

```text
src/
├── main/
│   ├── java/
│   │   └── com/compact/crm/
│   │       ├── controller/
│   │       ├── service/
│   │       ├── repository/
│   │       ├── entity/
│   │       ├── dto/
│   │       ├── security/
│   │       ├── specification/
│   │       └── ...
│   │
│   └── resources/
│       └── application.properties/
│
└── test/
