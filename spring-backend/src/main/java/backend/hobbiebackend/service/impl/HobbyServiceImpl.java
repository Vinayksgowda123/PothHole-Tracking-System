package backend.hobbiebackend.service.impl;

import backend.hobbiebackend.handler.NotFoundException;
import backend.hobbiebackend.model.entities.*;
import backend.hobbiebackend.model.entities.enums.CategoryNameEnum;
import backend.hobbiebackend.model.entities.enums.LocationEnum;
import backend.hobbiebackend.model.repostiory.HobbyRepository;
import backend.hobbiebackend.service.CategoryService;
import backend.hobbiebackend.service.HobbyService;
import backend.hobbiebackend.service.LocationService;
import backend.hobbiebackend.service.UserService;
import com.cloudinary.Cloudinary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@Transactional
public class HobbyServiceImpl implements HobbyService {
    private static final Logger LOGGER = LoggerFactory.getLogger(HobbyServiceImpl.class);

    private final HobbyRepository hobbyRepository;
    private final CategoryService categoryService;
    private final UserService userService;
    private final LocationService locationService;
    private final Cloudinary cloudinary;

    @Autowired
    public HobbyServiceImpl(HobbyRepository hobbyRepository, CategoryService categoryService, UserService userService, LocationService locationService, Cloudinary cloudinary) {
        this.hobbyRepository = hobbyRepository;
        this.categoryService = categoryService;
        this.userService = userService;
        this.locationService = locationService;
        this.cloudinary = cloudinary;
    }

    @Override
    public Hobby findHobbieById(Long id) {
        Optional<Hobby> hobby = this.hobbyRepository.findById(id);
        if (hobby.isPresent()) {
            return hobby.get();
        } else {
            throw new NotFoundException("This hobby does not exist");
        }
    }

    @Override
    public void saveUpdatedHobby(Hobby hobby) {
        Optional<Hobby> byId = this.hobbyRepository.findById(hobby.getId());
        if (byId.isPresent()) {
            deleteResourcesById(byId.get());
        }
        this.hobbyRepository.save(hobby);
    }

    @Override
    public boolean deleteHobby(long id) {
        Optional<Hobby> byId = this.hobbyRepository.findById(id);
        if (byId.isPresent()) {
            deleteResourcesById(byId.get());
            BusinessOwner business = null;
            try {
                business = this.userService.findBusinessByUsername(byId.get().getCreator());
            } catch (Exception e) {
                LOGGER.warn("Failed to find business by username: {}", byId.get().getCreator(), e);
            }

            if (business != null && business.getHobby_offers() != null) {
                business.getHobby_offers().remove(byId.get());
            } else {
                LOGGER.debug("Business is null or has no hobby_offers for creator={}", byId.get().getCreator());
            }

            try {
                this.userService.findAndRemoveHobbyFromClientsRecords(byId.get());
            } catch (Exception e) {
                LOGGER.warn("Failed to remove hobby from clients' records for hobby id={}: {}", id, e.getMessage());
            }

            this.hobbyRepository.deleteById(id);
            return true;
        }
        return false;
    }

    private void deleteResourcesById(Hobby byId) {
        List<String> resourceIds = new ArrayList<>();

        if (byId == null) {
            return;
        }

        addIfNotEmpty(resourceIds, byId.getProfileImg_id());
        addIfNotEmpty(resourceIds, byId.getGalleryImg1_id());
        addIfNotEmpty(resourceIds, byId.getGalleryImg2_id());
        addIfNotEmpty(resourceIds, byId.getGalleryImg3_id());

        if (resourceIds.isEmpty()) {
            return;
        }

        try {
            cloudinary.api().deleteResources(resourceIds, Map.of("invalidate", true));
        } catch (Exception e) {
            // Catch any exception from Cloudinary API or missing classes and log
            LOGGER.warn("Error when deleting cloud resources {}: {}", resourceIds, e.getMessage());
        }
    }

    private void addIfNotEmpty(List<String> list, String id) {
        if (id != null && !id.isBlank()) {
            list.add(id);
        }
    }

    @Override
    public Set<Hobby> findHobbyMatches(String username) {
        AppClient currentUserAppClient = this.userService.findAppClientByUsername(username);
        Set<Hobby> hobby_matches = new HashSet<>();
        if (currentUserAppClient == null) {
            return hobby_matches;
        }

        if (currentUserAppClient.getTestResults() != null) {
            LocationEnum location = currentUserAppClient.getTestResults().getLocation();
            Location locationByName = this.locationService.getLocationByName(location);
            List<Hobby> allByLocation = this.hobbyRepository.findAllByLocation(locationByName);

            List<CategoryNameEnum> testCategoryResults = new ArrayList<>();
            testCategoryResults.add(currentUserAppClient.getTestResults().getCategoryOne());
            testCategoryResults.add(currentUserAppClient.getTestResults().getCategoryTwo());
            testCategoryResults.add(currentUserAppClient.getTestResults().getCategoryThree());
            testCategoryResults.add(currentUserAppClient.getTestResults().getCategoryFour());
            testCategoryResults.add(currentUserAppClient.getTestResults().getCategoryFive());
            testCategoryResults.add(currentUserAppClient.getTestResults().getCategorySix());

            // Build a set for faster lookups and remove null categories
            Set<CategoryNameEnum> categorySet = new HashSet<>();
            for (CategoryNameEnum c : testCategoryResults) {
                if (c != null) categorySet.add(c);
            }

            if (allByLocation != null && !allByLocation.isEmpty() && !categorySet.isEmpty()) {
                List<Hobby> candidates = new ArrayList<>();
                for (Hobby h : allByLocation) {
                    if (h == null || h.getCategory() == null) continue;
                    if (categorySet.contains(h.getCategory().getName())) {
                        candidates.add(h);
                    }
                }

                // Shuffle and pick up to 10 unique random matches
                Collections.shuffle(candidates);
                int max = Math.min(10, candidates.size());
                for (int i = 0; i < max; i++) {
                    hobby_matches.add(candidates.get(i));
                }
            }
        }
        return hobby_matches;
    }

    @Override
    public boolean saveHobbyForClient(Hobby hobby, String username) {
        AppClient currentUserAppClient = this.userService.findAppClientByUsername(username);
        if (currentUserAppClient == null) {
            return false;
        }
        Optional<Hobby> hobbyById = this.hobbyRepository.findById(hobby.getId());
        List<Hobby> saved_hobbies = currentUserAppClient.getSaved_hobbies();
        if (saved_hobbies == null) {
            saved_hobbies = new ArrayList<>();
            currentUserAppClient.setSaved_hobbies(saved_hobbies);
        }
        if (hobbyById.isPresent() && !(saved_hobbies.contains(hobbyById.get()))) {
            saved_hobbies.add(hobbyById.get());
            return true;
        }
        return false;
    }

    @Override
    public boolean removeHobbyForClient(Hobby hobby, String username) {
        AppClient currentUserAppClient = this.userService.findAppClientByUsername(username);
        if (currentUserAppClient == null) {
            return false;
        }
        Optional<Hobby> hobbyById = this.hobbyRepository.findById(hobby.getId());
        List<Hobby> saved_hobbies = currentUserAppClient.getSaved_hobbies();
        if (saved_hobbies == null) {
            return false;
        }
        hobbyById.ifPresent(saved_hobbies::remove);
        return true;
    }

    @Override
    public boolean isHobbySaved(Long hobbyId, String username) {
        Optional<Hobby> byId = this.hobbyRepository.findById(hobbyId);
        if (byId.isPresent()) {
            AppClient currentUserAppClient = this.userService.findAppClientByUsername(username);
            if (currentUserAppClient == null || currentUserAppClient.getSaved_hobbies() == null) {
                return false;
            }
            return currentUserAppClient.getSaved_hobbies().contains(byId.get());
        }
        return false;
    }

    @Override
    public List<Hobby> findSavedHobbies(AppClient currentAppClient) {
        return currentAppClient.getSaved_hobbies();
    }

    @Override
    public Set<Hobby> getAllHobbiesForBusiness(String username) {
        return this.hobbyRepository.findAllByCreator(username);
    }

    @Override
    public Set<Hobby> getAllHobbieMatchesForClient(String username) {
        AppClient currentUserAppClient = this.userService.findAppClientByUsername(username);
        return currentUserAppClient.getHobby_matches();
    }

    @Override
    public void createHobby(Hobby offer) {
        this.hobbyRepository.save(offer);
    }

}